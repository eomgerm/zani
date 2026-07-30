from __future__ import annotations

from collections.abc import Iterator
from pathlib import Path

import numpy as np
import pytest

from zani_ai.engagement.contracts import ClipRecord, DatasetContract
from zani_ai.engagement.extraction import (
    ExtractionThresholdError,
    FrameResult,
    VideoFrame,
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


def test_extract_contract_writes_tokens_and_manifest(tmp_path: Path) -> None:
    output = tmp_path / "out"

    manifest = extract_contract(_contract(tmp_path), _AlwaysFace(), output, frame_source=_frames)

    assert manifest.schema == "mediapipe_98_v1"
    assert len(manifest.included) == 1
    with np.load(manifest.included[0].feature_path) as cached:
        assert cached["tokens"].shape == (20, 98)
        assert cached["label_index"].item() == 2
    assert (output / "manifest.json").is_file()


def test_extract_contract_fails_above_exclusion_threshold(tmp_path: Path) -> None:
    output = tmp_path / "out"

    with pytest.raises(ExtractionThresholdError) as error:
        extract_contract(_contract(tmp_path), _NeverFace(), output, frame_source=_frames)

    assert len(error.value.manifest.excluded) == 1
    assert error.value.manifest.excluded[0].reason == (
        "InsufficientTotalFaceCoverageError: window has 0 valid frames; 70 required"
    )
    assert (output / "manifest.json").is_file()


def test_extract_contract_reuses_matching_cache(tmp_path: Path) -> None:
    output = tmp_path / "out"
    contract = _contract(tmp_path)
    first = extract_contract(contract, _AlwaysFace(), output, frame_source=_frames)

    second = extract_contract(contract, _NeverFace(), output, frame_source=_frames)

    assert second.included[0].feature_path == first.included[0].feature_path


def test_extracted_cache_records_runtime_frame_gate(tmp_path: Path) -> None:
    output = tmp_path / "out"

    manifest = extract_contract(_contract(tmp_path), _AlwaysFace(), output, frame_source=_frames)

    with np.load(manifest.included[0].feature_path) as cached:
        assert cached["expected_frame_count"].item() == 100
        assert cached["minimum_valid_frame_ratio"].item() == 0.7


def test_extract_contract_does_not_reuse_cache_without_frame_gate_contract(
    tmp_path: Path,
) -> None:
    output = tmp_path / "out"
    contract = _contract(tmp_path)
    first = extract_contract(contract, _AlwaysFace(), output, frame_source=_frames)
    feature_path = first.included[0].feature_path
    with np.load(feature_path) as cached:
        legacy_payload = {
            key: np.asarray(cached[key])
            for key in cached.files
            if key not in {"expected_frame_count", "minimum_valid_frame_ratio"}
        }
    np.savez_compressed(feature_path, **legacy_payload)

    with pytest.raises(ExtractionThresholdError) as error:
        extract_contract(contract, _NeverFace(), output, frame_source=_frames)

    assert error.value.manifest.excluded[0].reason.startswith("InsufficientTotalFaceCoverageError")
