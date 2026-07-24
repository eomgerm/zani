"""Feature representation registry: derive feature caches from the raw MediaPipe cache.

`raw_cache.py` (Task 3) persists one MediaPipe pass per clip as a raw npz
(`landmarks[F,478,3]`, `transform[F,4,4]`, `blendshapes[F,52]` ordered by
`features.BLENDSHAPE_NAMES_132`, `timestamps_ms[F]`, `valid_mask[F]`) plus a
raw `manifest.json` (`ExtractionManifest`-shaped) at `raw_root/manifest.json`
listing the canonical included/excluded clip set.

This module turns that raw cache into one or more *representations* -- fixed
feature tensors derived from the same raw per-frame data -- without
re-running MediaPipe. Each `Representation` declares its own `name` and
`output_shape` and knows how to `build` its tensor from one loaded raw clip.
`build_feature_manifest` drives a representation across every raw-included
clip in a dataset contract and writes a token-npz cache plus a manifest in
the exact `ExtractionManifest` JSON shape `training._load_feature_datasets`
and `experiment._validate_manifest` already consume (schema/included/
excluded/counts/status/complete). Today, the manifest is a drop-in feature
root for training and experiment only when schema is `mediapipe_98_v1`;
schema-generalized (132D) consumption is wired in subsequent tasks.

`raw_clip` design: `raw_cache.RawClip` already mirrors the raw npz keys
field-for-field, so `Representation.build` takes a `RawClip` directly
(loaded from disk here by `load_raw_clip`) rather than inventing a new
wrapper dataclass.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol, cast

import numpy as np
from numpy.typing import NDArray

from zani_ai.engagement.contracts import DatasetContract, SplitName
from zani_ai.engagement.extraction import (
    SEGMENT_COUNT,
    ExcludedClip,
    ExtractionManifest,
    IncludedClip,
    _unique_temporary_path,
    _write_manifest,
)
from zani_ai.engagement.features import (
    BLENDSHAPE_NAMES_132,
    FeatureSchema,
    InvalidFrameFeaturesError,
    extract_frame_features,
)
from zani_ai.engagement.raw_cache import RAW_SCHEMA_NAME, RawClip
from zani_ai.engagement.segments import (
    InsufficientFaceCoverageError,
    TimedFeatures,
    aggregate_segments,
)

LANDMARK_SEQUENCE_NAME = "landmark_78_v1"
LANDMARK_SEQUENCE_SHAPE: tuple[int, int, int] = (3, 100, 78)


class Representation(Protocol):
    """A named, fixed-shape feature tensor derivable from one raw clip."""

    name: str
    output_shape: tuple[int, ...]

    def build(self, raw_clip: RawClip) -> NDArray[np.float32]:
        """Derive this representation's feature tensor for one raw clip."""
        ...


@dataclass(frozen=True, slots=True)
class TokenRepresentation:
    """Reproduces the E0 98D/132D token pipeline from a raw MediaPipe cache.

    For each frame, reconstructs the per-name blendshape mapping from the
    raw `blendshapes` array (ordered by `BLENDSHAPE_NAMES_132`) and calls
    `extract_frame_features(..., schema=self.schema)`; invalid frames become
    `TimedFeatures(..., values=None)`. `aggregate_segments` then buckets and
    aggregates exactly as the original per-clip extraction pipeline did.
    """

    schema: FeatureSchema

    @property
    def name(self) -> str:
        return self.schema.name

    @property
    def output_shape(self) -> tuple[int, int]:
        return (SEGMENT_COUNT, self.schema.token_feature_count)

    def build(self, raw_clip: RawClip) -> NDArray[np.float32]:
        frame_count = raw_clip.timestamps_ms.shape[0]
        timed: list[TimedFeatures] = []
        for index in range(frame_count):
            if raw_clip.valid_mask[index]:
                blendshapes = {
                    blendshape_name: float(raw_clip.blendshapes[index, position])
                    for position, blendshape_name in enumerate(BLENDSHAPE_NAMES_132)
                }
                values = extract_frame_features(
                    raw_clip.landmarks[index],
                    raw_clip.transform[index],
                    blendshapes,
                    schema=self.schema,
                )
            else:
                values = None
            timestamp_seconds = float(raw_clip.timestamps_ms[index]) / 1000.0
            timed.append(TimedFeatures(timestamp_seconds, values))
        return aggregate_segments(
            timed,
            raw_feature_count=self.schema.raw_feature_count,
            token_feature_count=self.schema.token_feature_count,
        )


@dataclass(frozen=True, slots=True)
class LandmarkSequenceRepresentation:
    """Stub for a future raw landmark-sequence representation (E1/E2).

    Declares its intended name/shape so callers can register it, but
    deliberately does not implement `build` -- that work is out of scope for
    Task 4.
    """

    name: str = LANDMARK_SEQUENCE_NAME
    output_shape: tuple[int, int, int] = LANDMARK_SEQUENCE_SHAPE

    def build(self, raw_clip: RawClip) -> NDArray[np.float32]:
        raise NotImplementedError(
            f"{LANDMARK_SEQUENCE_NAME} representation is not implemented (E1/E2)"
        )


def load_raw_clip(feature_path: Path) -> RawClip:
    """Load one raw MediaPipe cache npz (Task 3's format) into a `RawClip`."""
    with np.load(feature_path, allow_pickle=False) as cache:
        return RawClip(
            landmarks=np.asarray(cache["landmarks"]),
            transform=np.asarray(cache["transform"]),
            blendshapes=np.asarray(cache["blendshapes"]),
            timestamps_ms=np.asarray(cache["timestamps_ms"]),
            valid_mask=np.asarray(cache["valid_mask"]),
        )


def _save_representation_tokens(
    output_root: Path,
    clip_id: str,
    split: SplitName,
    tokens: NDArray[np.float32],
    label_index: int,
    schema_name: str,
    source_fingerprint: str,
) -> Path:
    """Save one clip's representation tokens in the training-consumed npz shape.

    Mirrors `extraction._save_tokens`'s npz contents (`tokens`, `label_index`
    int64, `schema` str, `source_fingerprint` str) but is parameterized by
    representation name/directory instead of hard-coding the frozen 98D
    schema, since a representation cache lives at
    `output_root/<representation.name>/<split>/<clip_id>.npz`.
    """
    directory = output_root / schema_name / split
    directory.mkdir(parents=True, exist_ok=True)
    feature_path = directory / f"{clip_id}.npz"
    temporary = _unique_temporary_path(feature_path)
    try:
        with temporary.open("wb") as file:
            np.savez_compressed(
                file,
                tokens=tokens,
                label_index=np.int64(label_index),
                schema=np.asarray(schema_name),
                source_fingerprint=np.asarray(source_fingerprint),
            )
        temporary.replace(feature_path)
    finally:
        temporary.unlink(missing_ok=True)
    return feature_path


def build_feature_manifest(
    raw_root: Path,
    output_root: Path,
    representation: Representation,
    contract: DatasetContract,
) -> Path:
    """Derive `representation`'s feature cache for every raw-included clip.

    Reads `raw_root/manifest.json` (Task 3's raw manifest), and for each
    canonically included clip: loads its raw npz, runs
    `representation.build`, and saves the resulting tensor to
    `output_root/<representation.name>/<split>/<clip_id>.npz`. Writes
    `output_root/manifest.json` in the same `ExtractionManifest` JSON shape
    the raw manifest and the frozen 98D manifest use, with
    `schema=representation.name`. Raw-manifest exclusions are carried
    forward; clips whose `representation.build` output is missing, wrong-
    shaped, or non-finite are newly excluded here with a clear reason.

    Returns the path to the written `output_root/manifest.json`.
    """
    raw_manifest_path = raw_root / "manifest.json"
    if not raw_manifest_path.is_file():
        raise FileNotFoundError(f"raw manifest not found: {raw_manifest_path}")
    raw_payload = json.loads(raw_manifest_path.read_text(encoding="utf-8"))
    if not isinstance(raw_payload, dict):
        raise ValueError("raw manifest must be a JSON object")
    if raw_payload.get("schema") != RAW_SCHEMA_NAME:
        raise ValueError(f"raw manifest schema must be {RAW_SCHEMA_NAME}")
    if raw_payload.get("status") != "complete" or raw_payload.get("complete") is not True:
        raise ValueError(
            f"raw manifest is incomplete (status={raw_payload.get('status')!r}); "
            "finish raw extraction first"
        )
    raw_included = raw_payload.get("included")
    raw_excluded = raw_payload.get("excluded")
    if not isinstance(raw_included, list) or not isinstance(raw_excluded, list):
        raise ValueError("raw manifest must contain included and excluded lists")

    label_index_by_clip: dict[tuple[str, str], int] = {
        (record.split, record.clip_id): record.label_index
        for split_records in contract.splits.values()
        for record in split_records
    }

    included: list[IncludedClip] = []
    excluded: list[ExcludedClip] = [
        ExcludedClip(
            clip_id=str(item["clip_id"]),
            split=cast(SplitName, item["split"]),
            reason=f"raw_cache excluded: {item['reason']}",
        )
        for item in raw_excluded
    ]

    for item in raw_included:
        clip_id = str(item["clip_id"])
        split = cast(SplitName, item["split"])
        source_fingerprint = str(item["source_fingerprint"])
        key = (split, clip_id)
        if key not in label_index_by_clip:
            excluded.append(
                ExcludedClip(clip_id, split, "clip not present in dataset contract")
            )
            continue
        label_index = label_index_by_clip[key]
        raw_feature_path = raw_root / str(item["feature_path"])
        try:
            raw_clip = load_raw_clip(raw_feature_path)
            tokens = np.asarray(representation.build(raw_clip), dtype=np.float32)
            if tokens.shape != representation.output_shape:
                raise ValueError(
                    f"representation {representation.name} produced shape "
                    f"{tokens.shape}, expected {representation.output_shape}"
                )
            if not np.isfinite(tokens).all():
                raise ValueError(
                    f"representation {representation.name} produced non-finite "
                    "token values"
                )
        except (ValueError, OSError, KeyError) as error:
            excluded.append(ExcludedClip(clip_id, split, f"{type(error).__name__}: {error}"))
            continue
        feature_path = _save_representation_tokens(
            output_root,
            clip_id,
            split,
            tokens,
            label_index,
            representation.name,
            source_fingerprint,
        )
        included.append(
            IncludedClip(clip_id, split, label_index, feature_path, source_fingerprint)
        )

    total = len(raw_included) + len(raw_excluded)
    manifest = ExtractionManifest(
        schema=representation.name,
        included=tuple(included),
        excluded=tuple(excluded),
        status="complete",
        total_count=total,
        cached_count=0,
    )
    _write_manifest(output_root, manifest)
    return output_root / "manifest.json"


__all__ = [
    "LANDMARK_SEQUENCE_NAME",
    "LANDMARK_SEQUENCE_SHAPE",
    "LandmarkSequenceRepresentation",
    "Representation",
    "TokenRepresentation",
    "build_feature_manifest",
    "load_raw_clip",
]
