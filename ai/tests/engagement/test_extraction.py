from __future__ import annotations

import json
from collections.abc import Iterator
from pathlib import Path

import numpy as np
import pytest

from zani_ai.engagement.contracts import ClipRecord, DatasetContract
from zani_ai.engagement.extraction import (
    DerivedFeatureProvenance,
    ExtractionManifest,
    ExtractionThresholdError,
    FrameResult,
    VideoFrame,
    _write_manifest,
    extract_contract,
)


class _AlwaysFace:
    def detect(self, rgb_frame: np.ndarray, timestamp_ms: int) -> FrameResult:
        del rgb_frame, timestamp_ms
        landmarks = np.zeros((478, 3), dtype=np.float32)
        landmarks[33, :2] = (0.1, 0.5)
        landmarks[133, :2] = (0.3, 0.5)
        landmarks[159, :2] = (0.2, 0.4)
        landmarks[145, :2] = (0.2, 0.6)
        landmarks[468:473, :2] = (0.2, 0.5)
        landmarks[263, :2] = (0.9, 0.5)
        landmarks[362, :2] = (0.7, 0.5)
        landmarks[386, :2] = (0.8, 0.4)
        landmarks[374, :2] = (0.8, 0.6)
        landmarks[473:478, :2] = (0.8, 0.5)
        landmarks[1, :2] = (0.5, 0.5)
        return FrameResult(landmarks, np.eye(4, dtype=np.float32), {})


class _NeverFace:
    def detect(self, rgb_frame: np.ndarray, timestamp_ms: int) -> None:
        del rgb_frame, timestamp_ms
        return None


def _frames(_: Path) -> Iterator[VideoFrame]:
    image = np.zeros((2, 2, 3), dtype=np.uint8)
    for timestamp_ms in range(0, 10_000, 100):
        yield VideoFrame(timestamp_ms, image)


def _contract(tmp_path: Path) -> DatasetContract:
    video = tmp_path / "clip.mp4"
    video.touch()
    record = ClipRecord("clip", "Engaged", 2, "train", video, "subject")
    return DatasetContract(tmp_path, {"train": (record,), "valid": (), "test": ()})


class _DriftingFace(_AlwaysFace):
    """A landmarker whose output shifts with every clip it has already seen.

    Stands in for MediaPipe's VIDEO running mode, where a `detect` call is
    conditioned on the previous call's tracking state. A landmarker reused across
    clips therefore folds one clip's closing frames into the next clip's features.
    A restarting timeline marks a clip boundary, which is what the real adapter's
    timestamp translation used to paper over.
    """

    def __init__(self) -> None:
        self._clips_seen = -1
        self._previous_timestamp_ms: int | None = None

    def detect(self, rgb_frame: np.ndarray, timestamp_ms: int) -> FrameResult:
        if self._previous_timestamp_ms is None or timestamp_ms <= self._previous_timestamp_ms:
            self._clips_seen += 1
        self._previous_timestamp_ms = timestamp_ms
        result = super().detect(rgb_frame, timestamp_ms)
        drifted = result.landmarks.copy()
        drifted[1, 0] += 0.01 * self._clips_seen
        return FrameResult(drifted, result.transform, result.blendshapes)


def _contract_of(tmp_path: Path, clip_ids: tuple[str, ...]) -> DatasetContract:
    records = []
    for clip_id in clip_ids:
        video = tmp_path / f"{clip_id}.mp4"
        video.touch()
        records.append(ClipRecord(clip_id, "Engaged", 2, "train", video, clip_id))
    return DatasetContract(tmp_path, {"train": tuple(records), "valid": (), "test": ()})


def _tokens_by_clip(manifest: object) -> dict[str, np.ndarray]:
    tokens = {}
    for clip in manifest.included:  # type: ignore[attr-defined]
        with np.load(clip.feature_path) as cached:
            tokens[clip.clip_id] = np.asarray(cached["tokens"])
    return tokens


def test_extract_contract_features_do_not_depend_on_clip_order(tmp_path: Path) -> None:
    """Each clip must be extracted by a landmarker that has seen no other clip.

    Sharing one landmarker across clips made the 98D cache depend on iteration
    order and worker count, so two runs over the same videos produced different
    datasets while recording the same fingerprint.
    """
    clip_ids = ("clip_a", "clip_b", "clip_c")

    forward = extract_contract(
        _contract_of(tmp_path, clip_ids),
        _DriftingFace,
        tmp_path / "forward",
        frame_source=_frames,
    )
    backward = extract_contract(
        _contract_of(tmp_path, tuple(reversed(clip_ids))),
        _DriftingFace,
        tmp_path / "backward",
        frame_source=_frames,
    )

    forward_tokens = _tokens_by_clip(forward)
    backward_tokens = _tokens_by_clip(backward)
    assert sorted(forward_tokens) == sorted(clip_ids)
    for clip_id in clip_ids:
        assert np.array_equal(forward_tokens[clip_id], backward_tokens[clip_id])


def test_drifting_face_double_proves_a_shared_landmarker_would_be_caught(
    tmp_path: Path,
) -> None:
    """Guard the guard: the double must actually leak when a landmarker is shared.

    Without this, `_DriftingFace` could stop drifting and the order-independence
    test above would keep passing for the wrong reason.
    """
    clip_ids = ("clip_a", "clip_b", "clip_c")
    shared = _DriftingFace()
    forward = extract_contract(
        _contract_of(tmp_path, clip_ids),
        lambda: shared,
        tmp_path / "forward",
        frame_source=_frames,
    )
    shared = _DriftingFace()
    backward = extract_contract(
        _contract_of(tmp_path, tuple(reversed(clip_ids))),
        lambda: shared,
        tmp_path / "backward",
        frame_source=_frames,
    )

    forward_tokens = _tokens_by_clip(forward)
    backward_tokens = _tokens_by_clip(backward)
    differing = [
        clip_id
        for clip_id in clip_ids
        if not np.array_equal(forward_tokens[clip_id], backward_tokens[clip_id])
    ]
    assert differing, "the double no longer models VIDEO-mode state leaking between clips"


def _derived_manifest() -> ExtractionManifest:
    """A manifest shaped the way `build-features` writes one."""
    return ExtractionManifest(
        schema="mediapipe_98_v1",
        included=(),
        excluded=(),
        status="complete",
        total_count=0,
        cached_count=0,
        provenance=DerivedFeatureProvenance(
            raw_schema="raw_frames_v1",
            raw_manifest_sha256="0" * 64,
            representation_name="mediapipe_98_v1",
            expected_frame_count=100,
            minimum_valid_frame_ratio=0.7,
            window_seconds=10.0,
            segment_count=20,
            minimum_valid_frames=3,
            representation_source_sha256="1" * 64,
            representation_dependencies_sha256="2" * 64,
            segment_aggregation_source_sha256="3" * 64,
            representation_fingerprint="4" * 64,
        ),
    )


def test_extract_contract_refuses_to_overwrite_a_build_features_manifest(tmp_path: Path) -> None:
    """`extract` and `build-features` share `<output_root>`, so one must not clobber
    the other's manifest and the feature cache beside it."""
    output = tmp_path / "out"
    output.mkdir()
    manifest_path = output / "manifest.json"
    foreign = json.dumps(_derived_manifest().to_json_dict(output))
    manifest_path.write_text(foreign, encoding="utf-8")

    with pytest.raises(FileExistsError) as error:
        extract_contract(_contract(tmp_path), _AlwaysFace, output, frame_source=_frames)

    assert "build-features" in str(error.value)
    assert manifest_path.read_text(encoding="utf-8") == foreign


def test_extract_contract_refuses_a_build_features_manifest_written_before_pipeline_was_recorded(
    tmp_path: Path,
) -> None:
    """Manifests already on disk predate the `pipeline` field, so the fallback that
    infers the writer from `representation_name` has to protect them too."""
    output = tmp_path / "out"
    output.mkdir()
    manifest_path = output / "manifest.json"
    payload = _derived_manifest().to_json_dict(output)
    del payload["pipeline"]
    foreign = json.dumps(payload)
    manifest_path.write_text(foreign, encoding="utf-8")

    with pytest.raises(FileExistsError) as error:
        extract_contract(_contract(tmp_path), _AlwaysFace, output, frame_source=_frames)

    assert "build-features" in str(error.value)
    assert manifest_path.read_text(encoding="utf-8") == foreign


def test_write_manifest_refuses_to_overwrite_an_extract_manifest(tmp_path: Path) -> None:
    """The reverse direction: this is the one that destroyed a frozen 98D dataset."""
    output = tmp_path / "out"
    extract_contract(_contract(tmp_path), _AlwaysFace, output, frame_source=_frames)
    manifest_path = output / "manifest.json"
    before = manifest_path.read_text(encoding="utf-8")

    with pytest.raises(FileExistsError) as error:
        _write_manifest(output, _derived_manifest())

    assert "extract" in str(error.value)
    assert manifest_path.read_text(encoding="utf-8") == before


def test_extract_contract_writes_tokens_and_manifest(tmp_path: Path) -> None:
    output = tmp_path / "out"

    manifest = extract_contract(_contract(tmp_path), _AlwaysFace, output, frame_source=_frames)

    assert manifest.schema == "mediapipe_98_v1"
    assert len(manifest.included) == 1
    with np.load(manifest.included[0].feature_path) as cached:
        assert cached["tokens"].shape == (20, 98)
        assert cached["label_index"].item() == 2
    assert (output / "manifest.json").is_file()


def test_extract_contract_fails_above_exclusion_threshold(tmp_path: Path) -> None:
    output = tmp_path / "out"

    with pytest.raises(ExtractionThresholdError) as error:
        extract_contract(_contract(tmp_path), _NeverFace, output, frame_source=_frames)

    assert len(error.value.manifest.excluded) == 1
    assert error.value.manifest.excluded[0].reason == (
        "InsufficientTotalFaceCoverageError: window has 0 valid frames; 70 required"
    )
    assert (output / "manifest.json").is_file()


def test_extract_contract_reuses_matching_cache(tmp_path: Path) -> None:
    output = tmp_path / "out"
    contract = _contract(tmp_path)
    first = extract_contract(contract, _AlwaysFace, output, frame_source=_frames)

    second = extract_contract(contract, _NeverFace, output, frame_source=_frames)

    assert second.included[0].feature_path == first.included[0].feature_path


def test_extracted_cache_records_runtime_frame_gate(tmp_path: Path) -> None:
    output = tmp_path / "out"

    manifest = extract_contract(_contract(tmp_path), _AlwaysFace, output, frame_source=_frames)

    with np.load(manifest.included[0].feature_path) as cached:
        assert cached["expected_frame_count"].item() == 100
        assert cached["minimum_valid_frame_ratio"].item() == 0.7


def test_extract_contract_does_not_reuse_cache_without_frame_gate_contract(
    tmp_path: Path,
) -> None:
    output = tmp_path / "out"
    contract = _contract(tmp_path)
    first = extract_contract(contract, _AlwaysFace, output, frame_source=_frames)
    feature_path = first.included[0].feature_path
    with np.load(feature_path) as cached:
        legacy_payload = {
            key: np.asarray(cached[key])
            for key in cached.files
            if key not in {"expected_frame_count", "minimum_valid_frame_ratio"}
        }
    np.savez_compressed(feature_path, **legacy_payload)

    with pytest.raises(ExtractionThresholdError) as error:
        extract_contract(contract, _NeverFace, output, frame_source=_frames)

    assert error.value.manifest.excluded[0].reason.startswith("InsufficientTotalFaceCoverageError")
