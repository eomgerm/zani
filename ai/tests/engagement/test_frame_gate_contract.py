from __future__ import annotations

import json
import re
from collections.abc import Iterator
from pathlib import Path

import numpy as np
import pytest

from zani_ai.engagement import segments
from zani_ai.engagement.contracts import ClipRecord, DatasetContract
from zani_ai.engagement.extraction import FrameResult, VideoFrame, _build_provenance
from zani_ai.engagement.features import get_schema
from zani_ai.engagement.raw_cache import (
    InsufficientRawCoverageError,
    _build_raw_provenance,
    _process_raw_clip,
    collect_raw_clip,
    is_clip_included,
)
from zani_ai.engagement.representations import (
    TokenRepresentation,
    build_feature_manifest,
)


def _runtime_config_value(name: str) -> float:
    repository_root = Path(__file__).parents[3]
    source = (
        repository_root
        / "fe"
        / "src"
        / "domains"
        / "attention"
        / "domain"
        / "attentionDetectionConfig.ts"
    ).read_text(encoding="utf-8")
    match = re.search(rf"\b{name}:\s*([\d.]+)", source)
    if match is None:
        raise AssertionError(f"runtime attention config has no numeric {name}")
    return float(match.group(1))


def _valid_mask(counts: list[int]) -> np.ndarray:
    mask = np.zeros(100, dtype=np.bool_)
    for segment, valid_count in enumerate(counts):
        start = segment * 5
        mask[start : start + valid_count] = True
    return mask


class _SixtyNineFaceLandmarker:
    def detect(self, rgb_frame: np.ndarray, timestamp_ms: int) -> FrameResult | None:
        del rgb_frame
        segment = timestamp_ms // 500
        offset = timestamp_ms % 500 // 100
        valid_count = 4 if segment < 9 else 3
        if offset >= valid_count:
            return None
        return FrameResult(
            np.zeros((478, 3), dtype=np.float32),
            np.eye(4, dtype=np.float32),
            {},
        )


class _TwoHundredNineFaceLandmarker:
    def __init__(self) -> None:
        self.index = 0

    def detect(self, rgb_frame: np.ndarray, timestamp_ms: int) -> FrameResult | None:
        del rgb_frame, timestamp_ms
        index = self.index
        self.index += 1
        segment, offset = divmod(index, 15)
        valid_count = 11 if segment < 9 else 10
        if offset >= valid_count:
            return None
        return FrameResult(
            np.zeros((478, 3), dtype=np.float32),
            np.eye(4, dtype=np.float32),
            {},
        )


def _hundred_frames(_: Path) -> Iterator[VideoFrame]:
    image = np.zeros((2, 2, 3), dtype=np.uint8)
    for timestamp_ms in range(0, 10_000, 100):
        yield VideoFrame(timestamp_ms, image)


def _three_hundred_frames(_: Path) -> Iterator[VideoFrame]:
    image = np.zeros((2, 2, 3), dtype=np.uint8)
    for index in range(300):
        yield VideoFrame(round(index * 1000 / 30), image)


def test_python_frame_gate_matches_browser_runtime_contract() -> None:
    assert getattr(segments, "EXPECTED_FRAME_COUNT", None) == _runtime_config_value(
        "expectedFrameCount"
    )
    assert getattr(segments, "MINIMUM_VALID_FRAME_RATIO", None) == _runtime_config_value(
        "minimumValidFrameRatio"
    )


def test_raw_cache_rejects_sixty_to_sixty_nine_valid_frames() -> None:
    timestamps_ms = np.arange(0, 10_000, 100, dtype=np.int32)

    assert not is_clip_included(_valid_mask([3] * 20), timestamps_ms)
    assert not is_clip_included(_valid_mask([4] * 9 + [3] * 11), timestamps_ms)


def test_raw_cache_accepts_seventy_valid_frames() -> None:
    timestamps_ms = np.arange(0, 10_000, 100, dtype=np.int32)

    assert is_clip_included(_valid_mask([4] * 10 + [3] * 10), timestamps_ms)


def test_feature_extraction_provenance_records_runtime_frame_gate(tmp_path: Path) -> None:
    model = tmp_path / "face_landmarker.task"
    model.write_bytes(b"model")

    provenance = _build_provenance(model, workers=2, max_excluded_fraction=0.05)

    assert provenance.expected_frame_count == 100
    assert provenance.minimum_valid_frame_ratio == 0.7


def test_raw_extraction_provenance_records_runtime_frame_gate(tmp_path: Path) -> None:
    model = tmp_path / "face_landmarker.task"
    model.write_bytes(b"model")

    provenance = _build_raw_provenance(
        model,
        workers=2,
        max_excluded_fraction=0.05,
    )

    assert provenance.expected_frame_count == 100
    assert provenance.minimum_valid_frame_ratio == 0.7

    thirty_fps = _build_raw_provenance(
        model,
        workers=2,
        max_excluded_fraction=0.05,
        sample_fps=30,
    )
    assert thirty_fps.expected_frame_count == 300


def test_raw_extraction_distinguishes_total_coverage_failure(tmp_path: Path) -> None:
    with pytest.raises(InsufficientRawCoverageError) as error:
        _process_raw_clip(
            tmp_path / "clip.mp4",
            _SixtyNineFaceLandmarker(),
            frame_source=_hundred_frames,
        )

    assert type(error.value).__name__ == "InsufficientRawTotalCoverageError"
    assert str(error.value) == "window has 69 valid frames; 70 required"


def test_raw_extraction_scales_total_gate_with_sample_rate(tmp_path: Path) -> None:
    with pytest.raises(InsufficientRawCoverageError) as error:
        _process_raw_clip(
            tmp_path / "clip.mp4",
            _TwoHundredNineFaceLandmarker(),
            frame_source=_three_hundred_frames,
            sample_fps=30,
        )

    assert type(error.value).__name__ == "InsufficientRawTotalCoverageError"
    assert str(error.value) == "window has 209 valid frames; 210 required"


def test_feature_manifest_rebuild_excludes_raw_clip_below_runtime_gate(
    tmp_path: Path,
) -> None:
    raw_root = tmp_path / "raw"
    raw_feature = raw_root / "train" / "clip.npz"
    raw_feature.parent.mkdir(parents=True)
    clip = collect_raw_clip(
        tmp_path / "clip.mp4",
        _SixtyNineFaceLandmarker(),
        frame_source=_hundred_frames,
    )
    np.savez_compressed(
        raw_feature,
        landmarks=clip.landmarks,
        transform=clip.transform,
        blendshapes=clip.blendshapes,
        timestamps_ms=clip.timestamps_ms,
        valid_mask=clip.valid_mask,
    )
    (raw_root / "manifest.json").write_text(
        json.dumps(
            {
                "schema": "raw_frames_v1",
                "status": "complete",
                "complete": True,
                "included": [
                    {
                        "clip_id": "clip",
                        "split": "train",
                        "feature_path": "train/clip.npz",
                        "source_fingerprint": "fixture",
                    }
                ],
                "excluded": [],
            }
        ),
        encoding="utf-8",
    )
    record = ClipRecord(
        "clip",
        "Engaged",
        2,
        "train",
        tmp_path / "clip.mp4",
        "subject",
    )
    contract = DatasetContract(
        tmp_path,
        {"train": (record,), "valid": (), "test": ()},
    )
    output = tmp_path / "features"

    manifest_path = build_feature_manifest(
        raw_root,
        output,
        TokenRepresentation(get_schema("mediapipe_98_v1")),
        contract,
    )

    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["included"] == []
    assert manifest["excluded"] == [
        {
            "clip_id": "clip",
            "split": "train",
            "reason": (
                "InsufficientTotalFaceCoverageError: "
                "window has 69 valid frames; 70 required"
            ),
        }
    ]
