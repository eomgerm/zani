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

import inspect
import json
from dataclasses import dataclass
from hashlib import sha256
from pathlib import Path
from typing import Protocol, cast

import numpy as np
from numpy.typing import NDArray

from zani_ai.engagement import features as feature_algorithms
from zani_ai.engagement.contracts import DatasetContract, SplitName
from zani_ai.engagement.extraction import (
    MINIMUM_VALID_FRAMES,
    SAMPLE_FPS,
    SEGMENT_COUNT,
    WINDOW_SECONDS,
    DerivedFeatureProvenance,
    ExcludedClip,
    ExtractionManifest,
    IncludedClip,
    _unique_temporary_path,
    _write_manifest,
)
from zani_ai.engagement.features import (
    BLENDSHAPE_NAMES_132,
    FeatureSchema,
    extract_frame_features,
)
from zani_ai.engagement.landmark_graph import LANDMARK_78_INDICES
from zani_ai.engagement.raw_cache import RawClip
from zani_ai.engagement.segments import (
    EXPECTED_FRAME_COUNT,
    MINIMUM_VALID_FRAME_RATIO,
    TimedFeatures,
    aggregate_segments,
    aggregate_segments_with_zero_placeholders,
)

LANDMARK_SEQUENCE_NAME = "landmark_78_v1"
LANDMARK_SEQUENCE_SHAPE: tuple[int, int, int] = (3, 100, 78)


def landmark_sequence_name(step_count: int) -> str:
    """The landmark-sequence schema for a step count.

    100 steps keeps the bare ``landmark_78_v1`` name so E1's existing feature
    cache and checkpoints stay valid; other lengths get their own name, and
    therefore their own cache directory and manifest schema.
    """
    if step_count == LANDMARK_SEQUENCE_SHAPE[1]:
        return LANDMARK_SEQUENCE_NAME
    return f"landmark_78_{step_count}_v1"


def landmark_sequence_placeholder_name(step_count: int) -> str:
    """The zero-placeholder landmark-sequence schema for a step count.

    Its own name, and therefore its own cache directory, because it holds
    different tensors than the forward-filled representation of the same length
    -- following how ``mediapipe_98_placeholder_v1`` sits beside
    ``mediapipe_98_v1``.
    """
    if step_count == LANDMARK_SEQUENCE_SHAPE[1]:
        return "landmark_78_placeholder_v1"
    return f"landmark_78_{step_count}_placeholder_v1"


class Representation(Protocol):
    """A named, fixed-shape feature tensor derivable from one raw clip."""

    @property
    def name(self) -> str: ...

    @property
    def output_shape(self) -> tuple[int, ...]: ...

    #: npz key the built tensor is saved under (see `_save_representation_tokens`).
    @property
    def array_key(self) -> str: ...

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

    @property
    def array_key(self) -> str:
        return "tokens"

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
class ZeroPlaceholderTokenRepresentation(TokenRepresentation):
    """98D token representation that keeps face-detection failures as zeros."""

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
        return aggregate_segments_with_zero_placeholders(
            timed,
            raw_feature_count=self.schema.raw_feature_count,
            token_feature_count=self.schema.token_feature_count,
        )


@dataclass(frozen=True, slots=True)
class LandmarkSequenceRepresentation:
    """Raw 78-landmark-sequence representation for the E1 ST-GCN pipeline.

    Aligns each raw clip to a `sample_fps` x 10s grid -- 100 steps at 10 FPS,
    300 at 30 FPS, matching whatever rate the raw cache was extracted at -- and
    for each grid step selects the raw frame landing on it that is
    `valid_mask`-valid, taking its `landmarks[LANDMARK_78_INDICES, :3]`
    (`[78, 3]`).

    A grid step with no matching valid raw frame (a dropped/undecodable
    sample, a no-face frame, or a clip shorter than 10s) is forward-filled
    from the nearest earlier valid step; steps before the first valid step
    take the first valid step's values. If the clip has no valid frame at
    all, `build` raises `ValueError` so `build_feature_manifest` excludes the
    clip, matching how `TokenRepresentation`'s failures (via
    `aggregate_segments`/`extract_frame_features`) are signalled.
    """

    name: str = LANDMARK_SEQUENCE_NAME
    output_shape: tuple[int, int, int] = LANDMARK_SEQUENCE_SHAPE
    array_key: str = "sequence"
    #: Must equal the rate the raw cache was extracted at, or grid steps will
    #: not line up with the cached timestamps.
    sample_fps: float = SAMPLE_FPS

    @classmethod
    def for_sample_fps(
        cls, sample_fps: float, *, window_seconds: float = WINDOW_SECONDS
    ) -> LandmarkSequenceRepresentation:
        """Build the representation matching a raw cache's sampling rate."""
        step_count = round(window_seconds * sample_fps)
        _, _, node_count = LANDMARK_SEQUENCE_SHAPE
        return cls(
            name=landmark_sequence_name(step_count),
            output_shape=(3, step_count, node_count),
            sample_fps=sample_fps,
        )

    def build(self, raw_clip: RawClip) -> NDArray[np.float32]:
        _, step_count, node_count = self.output_shape
        landmark_indices = np.asarray(LANDMARK_78_INDICES, dtype=np.intp)

        # Map each grid step (0..step_count-1) to the raw frame index that
        # lands on it, considering only valid frames; earliest match wins if
        # a step is somehow hit more than once.
        frame_index_by_step: dict[int, int] = {}
        for frame_index in range(raw_clip.timestamps_ms.shape[0]):
            if not raw_clip.valid_mask[frame_index]:
                continue
            # Scale by the rate rather than dividing by a rounded step size:
            # at 30 FPS a 33ms step accumulates error until the last frames
            # fall outside the grid entirely (step 299 would land on 302).
            step = round(float(raw_clip.timestamps_ms[frame_index]) * self.sample_fps / 1000.0)
            if 0 <= step < step_count and step not in frame_index_by_step:
                frame_index_by_step[step] = frame_index

        if not frame_index_by_step:
            raise ValueError(
                f"{self.name}: clip has no valid frame to build a landmark sequence from"
            )

        sequence = np.zeros((step_count, node_count, 3), dtype=np.float32)
        first_valid_step = min(frame_index_by_step)
        last_values = raw_clip.landmarks[frame_index_by_step[first_valid_step]][
            landmark_indices, :3
        ].astype(np.float32, copy=False)
        for step in range(first_valid_step, step_count):
            frame_index = frame_index_by_step.get(step)
            if frame_index is not None:
                last_values = raw_clip.landmarks[frame_index][landmark_indices, :3].astype(
                    np.float32, copy=False
                )
            sequence[step] = last_values
        # Steps before the first valid step: back-fill with the first valid step's values.
        sequence[:first_valid_step] = sequence[first_valid_step]

        return np.ascontiguousarray(sequence.transpose(2, 0, 1), dtype=np.float32)


@dataclass(frozen=True, slots=True)
class ZeroPlaceholderLandmarkSequenceRepresentation(LandmarkSequenceRepresentation):
    """Landmark sequence that leaves missing-face steps at zero instead of filling.

    arXiv:2403.17175 §5 reports classifying "samples with occluded or absent
    faces, i.e., no facial landmarks" as Not-Engaged, so absence is a signal the
    paper's model learns from. Forward-filling erases exactly that signal: it
    turns "no face for three seconds" into "a face that held perfectly still",
    which is a different -- and confidently wrong -- observation.

    Zeros carry it instead. MediaPipe landmark coordinates are never all-zero
    for a detected face, so an all-zero step is unambiguous. The three channels
    stay x/y/z: a fourth validity channel would change the first convolution's
    shape and with it the parameter count this reproduction is measured against.

    A clip with no valid frame at all yields an all-zero tensor rather than an
    exclusion, because that clip is one the paper trained on.
    """

    name: str = landmark_sequence_placeholder_name(LANDMARK_SEQUENCE_SHAPE[1])

    @classmethod
    def for_sample_fps(
        cls, sample_fps: float, *, window_seconds: float = WINDOW_SECONDS
    ) -> ZeroPlaceholderLandmarkSequenceRepresentation:
        step_count = round(window_seconds * sample_fps)
        _, _, node_count = LANDMARK_SEQUENCE_SHAPE
        return cls(
            name=landmark_sequence_placeholder_name(step_count),
            output_shape=(3, step_count, node_count),
            sample_fps=sample_fps,
        )

    def build(self, raw_clip: RawClip) -> NDArray[np.float32]:
        _, step_count, node_count = self.output_shape
        landmark_indices = np.asarray(LANDMARK_78_INDICES, dtype=np.intp)
        frame_index_by_step: dict[int, int] = {}
        for frame_index in range(raw_clip.timestamps_ms.shape[0]):
            if not raw_clip.valid_mask[frame_index]:
                continue
            step = round(float(raw_clip.timestamps_ms[frame_index]) * self.sample_fps / 1000.0)
            if 0 <= step < step_count and step not in frame_index_by_step:
                frame_index_by_step[step] = frame_index

        sequence = np.zeros((step_count, node_count, 3), dtype=np.float32)
        for step, frame_index in frame_index_by_step.items():
            sequence[step] = raw_clip.landmarks[frame_index][landmark_indices, :3]
        return np.ascontiguousarray(sequence.transpose(2, 0, 1), dtype=np.float32)


def _representation_dependencies_sha256(representation: Representation) -> str:
    """Hash values and source code that can change derived feature contents."""
    payload: dict[str, object] = {
        "array_key": representation.array_key,
        "name": representation.name,
        "output_shape": representation.output_shape,
    }
    if isinstance(representation, TokenRepresentation):
        payload.update(
            {
                "kind": "token",
                "feature_algorithms_source_sha256": sha256(
                    inspect.getsource(feature_algorithms).encode("utf-8")
                ).hexdigest(),
                "raw_blendshape_names": BLENDSHAPE_NAMES_132,
                "schema": {
                    "name": representation.schema.name,
                    "blendshape_names": representation.schema.blendshape_names,
                    "gaze_dim": representation.schema.gaze_dim,
                    "head_dim": representation.schema.head_dim,
                },
            }
        )
    elif isinstance(representation, LandmarkSequenceRepresentation):
        payload.update(
            {
                "kind": "landmark_sequence",
                "landmark_indices": LANDMARK_78_INDICES,
                "sample_fps": representation.sample_fps,
            }
        )
    else:
        payload["kind"] = type(representation).__qualname__
    return sha256(
        json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


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
    array_key: str,
    provenance: DerivedFeatureProvenance,
) -> Path:
    """Save one clip's representation tensor in the training-consumed npz shape.

    Mirrors `extraction._save_tokens`'s npz contents (array, `label_index`
    int64, `schema` str, `source_fingerprint` str) but is parameterized by
    representation name/directory/array key instead of hard-coding the
    frozen 98D schema, since a representation cache lives at
    `output_root/<representation.name>/<split>/<clip_id>.npz`. The array is
    stored under `array_key` (`"tokens"` for `TokenRepresentation`,
    `"sequence"` for `LandmarkSequenceRepresentation`) so existing E0/E0-A/
    E0-B consumers that read the `tokens` key keep working unchanged.
    """
    directory = output_root / schema_name / split
    directory.mkdir(parents=True, exist_ok=True)
    feature_path = directory / f"{clip_id}.npz"
    temporary = _unique_temporary_path(feature_path)
    try:
        with temporary.open("wb") as file:
            np.savez_compressed(
                file,
                label_index=np.int64(label_index),
                schema=np.asarray(schema_name),
                source_fingerprint=np.asarray(source_fingerprint),
                expected_frame_count=np.int64(provenance.expected_frame_count),
                minimum_valid_frame_ratio=np.float64(provenance.minimum_valid_frame_ratio),
                representation_fingerprint=np.asarray(provenance.representation_fingerprint),
                raw_manifest_sha256=np.asarray(provenance.raw_manifest_sha256),
                **{array_key: tokens},
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
    raw_manifest_bytes = raw_manifest_path.read_bytes()
    raw_payload = json.loads(raw_manifest_bytes.decode("utf-8"))
    if not isinstance(raw_payload, dict):
        raise ValueError("raw manifest must be a JSON object")
    raw_schema = raw_payload.get("schema")
    if not isinstance(raw_schema, str) or not raw_schema.startswith("raw_frames_"):
        raise ValueError(f"not a raw frame cache manifest: schema={raw_schema!r}")
    if raw_payload.get("status") != "complete" or raw_payload.get("complete") is not True:
        raise ValueError(
            f"raw manifest is incomplete (status={raw_payload.get('status')!r}); "
            "finish raw extraction first"
        )
    raw_included = raw_payload.get("included")
    raw_excluded = raw_payload.get("excluded")
    if not isinstance(raw_included, list) or not isinstance(raw_excluded, list):
        raise ValueError("raw manifest must contain included and excluded lists")

    # The frame gate the raw cache was actually built under, not this module's
    # 10 FPS constants. A 30 FPS cache expects 300 frames per window, and
    # stamping 100 into a 300-step cache's identity would make the two caches
    # look interchangeable. Missing on caches written before provenance
    # existed, where the constants are the values that were used.
    raw_provenance = raw_payload.get("provenance")
    if not isinstance(raw_provenance, dict):
        raw_provenance = {}
    raw_expected_frame_count = int(raw_provenance.get("expected_frame_count", EXPECTED_FRAME_COUNT))
    raw_minimum_valid_frames = int(raw_provenance.get("minimum_valid_frames", MINIMUM_VALID_FRAMES))
    raw_minimum_valid_frame_ratio = float(
        raw_provenance.get("minimum_valid_frame_ratio", MINIMUM_VALID_FRAME_RATIO)
    )
    # A landmark sequence samples the raw timestamps on its own grid, so a rate
    # mismatch does not fail -- it silently leaves two out of every three steps
    # empty. Refuse instead.
    representation_sample_fps = getattr(representation, "sample_fps", None)
    raw_sample_fps = raw_provenance.get("sample_fps")
    if (
        representation_sample_fps is not None
        and raw_sample_fps is not None
        and float(raw_sample_fps) != float(representation_sample_fps)
    ):
        raise ValueError(
            f"{representation.name} samples at {representation_sample_fps} FPS but "
            f"{raw_schema} was extracted at {raw_sample_fps} FPS"
        )

    representation_source_sha256 = sha256(
        inspect.getsource(type(representation)).encode("utf-8")
    ).hexdigest()
    representation_dependencies_sha256 = _representation_dependencies_sha256(representation)
    segment_aggregation = (
        aggregate_segments_with_zero_placeholders
        if isinstance(representation, ZeroPlaceholderTokenRepresentation)
        else aggregate_segments
    )
    segment_aggregation_source_sha256 = sha256(
        inspect.getsource(segment_aggregation).encode("utf-8")
    ).hexdigest()
    provenance_payload: dict[str, object] = {
        "raw_schema": raw_schema,
        "raw_manifest_sha256": sha256(raw_manifest_bytes).hexdigest(),
        "representation_name": representation.name,
        "expected_frame_count": raw_expected_frame_count,
        "minimum_valid_frame_ratio": raw_minimum_valid_frame_ratio,
        "window_seconds": WINDOW_SECONDS,
        "segment_count": SEGMENT_COUNT,
        "minimum_valid_frames": raw_minimum_valid_frames,
        "representation_source_sha256": representation_source_sha256,
        "representation_dependencies_sha256": representation_dependencies_sha256,
        "segment_aggregation_source_sha256": segment_aggregation_source_sha256,
    }
    representation_fingerprint = sha256(
        json.dumps(
            provenance_payload,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    provenance = DerivedFeatureProvenance(
        raw_schema=raw_schema,
        raw_manifest_sha256=sha256(raw_manifest_bytes).hexdigest(),
        representation_name=representation.name,
        expected_frame_count=raw_expected_frame_count,
        minimum_valid_frame_ratio=raw_minimum_valid_frame_ratio,
        window_seconds=WINDOW_SECONDS,
        segment_count=SEGMENT_COUNT,
        minimum_valid_frames=raw_minimum_valid_frames,
        representation_source_sha256=representation_source_sha256,
        representation_dependencies_sha256=representation_dependencies_sha256,
        segment_aggregation_source_sha256=segment_aggregation_source_sha256,
        representation_fingerprint=representation_fingerprint,
    )

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
            excluded.append(ExcludedClip(clip_id, split, "clip not present in dataset contract"))
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
                    f"representation {representation.name} produced non-finite token values"
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
            representation.array_key,
            provenance,
        )
        included.append(IncludedClip(clip_id, split, label_index, feature_path, source_fingerprint))

    total = len(raw_included) + len(raw_excluded)
    manifest = ExtractionManifest(
        schema=representation.name,
        included=tuple(included),
        excluded=tuple(excluded),
        status="complete",
        total_count=total,
        cached_count=0,
        provenance=provenance,
    )
    _write_manifest(output_root, manifest)
    return output_root / "manifest.json"


__all__ = [
    "LANDMARK_SEQUENCE_NAME",
    "LANDMARK_SEQUENCE_SHAPE",
    "LandmarkSequenceRepresentation",
    "Representation",
    "TokenRepresentation",
    "ZeroPlaceholderLandmarkSequenceRepresentation",
    "ZeroPlaceholderTokenRepresentation",
    "build_feature_manifest",
    "landmark_sequence_name",
    "landmark_sequence_placeholder_name",
    "load_raw_clip",
]
