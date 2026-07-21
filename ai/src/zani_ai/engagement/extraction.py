from __future__ import annotations

import json
from collections.abc import Callable, Iterator, Mapping
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Protocol

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.core.base_options import BaseOptions
from numpy.typing import NDArray

from zani_ai.engagement.contracts import ClipRecord, DatasetContract, SplitName
from zani_ai.engagement.features import (
    SCHEMA_NAME,
    InvalidFrameFeaturesError,
    extract_frame_features,
)
from zani_ai.engagement.segments import (
    InsufficientFaceCoverageError,
    TimedFeatures,
    aggregate_segments,
)

type FrameSource = Callable[[Path], Iterator["VideoFrame"]]


class VideoDecodeError(RuntimeError):
    """Raised when OpenCV cannot read a requested video."""


@dataclass(frozen=True, slots=True)
class VideoFrame:
    timestamp_ms: int
    rgb: NDArray[np.uint8]


@dataclass(frozen=True, slots=True)
class FrameResult:
    landmarks: NDArray[np.float32]
    transform: NDArray[np.float32]
    blendshapes: Mapping[str, float]


class FrameLandmarker(Protocol):
    def detect(
        self, rgb_frame: NDArray[np.uint8], timestamp_ms: int
    ) -> FrameResult | None: ...


class MediaPipeFaceLandmarker:
    """Synchronous MediaPipe Tasks adapter for offline video extraction."""

    def __init__(self, model_asset_path: Path) -> None:
        if not model_asset_path.is_file():
            raise FileNotFoundError(f"Face Landmarker model not found: {model_asset_path}")
        options = vision.FaceLandmarkerOptions(
            base_options=BaseOptions(model_asset_path=str(model_asset_path)),
            running_mode=vision.RunningMode.VIDEO,
            num_faces=1,
            output_face_blendshapes=True,
            output_facial_transformation_matrixes=True,
        )
        self._landmarker = vision.FaceLandmarker.create_from_options(options)

    def detect(
        self, rgb_frame: NDArray[np.uint8], timestamp_ms: int
    ) -> FrameResult | None:
        image = mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb_frame)
        result = self._landmarker.detect_for_video(image, timestamp_ms)
        if not result.face_landmarks:
            return None
        landmarks = np.asarray(
            [(point.x, point.y, point.z) for point in result.face_landmarks[0]],
            dtype=np.float32,
        )
        if not result.facial_transformation_matrixes:
            raise InvalidFrameFeaturesError("MediaPipe did not return a face transform")
        transform = np.asarray(result.facial_transformation_matrixes[0], dtype=np.float32)
        categories = result.face_blendshapes[0] if result.face_blendshapes else []
        blendshapes = {category.category_name: float(category.score) for category in categories}
        return FrameResult(landmarks, transform, blendshapes)

    def close(self) -> None:
        self._landmarker.close()

    def __enter__(self) -> MediaPipeFaceLandmarker:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


@dataclass(frozen=True, slots=True)
class IncludedClip:
    clip_id: str
    split: SplitName
    label_index: int
    feature_path: Path
    source_fingerprint: str


@dataclass(frozen=True, slots=True)
class ExcludedClip:
    clip_id: str
    split: SplitName
    reason: str


@dataclass(frozen=True, slots=True)
class ExtractionManifest:
    schema: str
    included: tuple[IncludedClip, ...]
    excluded: tuple[ExcludedClip, ...]

    def to_json_dict(self, root: Path) -> dict[str, object]:
        return {
            "schema": self.schema,
            "included": [
                {
                    **asdict(item),
                    "feature_path": item.feature_path.relative_to(root).as_posix(),
                }
                for item in self.included
            ],
            "excluded": [asdict(item) for item in self.excluded],
        }


class ExtractionThresholdError(RuntimeError):
    def __init__(self, manifest: ExtractionManifest, fraction: float) -> None:
        self.manifest = manifest
        super().__init__(f"excluded {fraction:.1%} of clips; maximum allowed is 5.0%")


def iter_sampled_frames(
    video_path: Path, sample_fps: float = 10.0, window_seconds: float = 10.0
) -> Iterator[VideoFrame]:
    """Seek to deterministic timestamps instead of depending on source FPS."""
    capture = cv2.VideoCapture(str(video_path))
    try:
        if not capture.isOpened():
            raise VideoDecodeError(f"could not open video: {video_path}")
        source_fps = float(capture.get(cv2.CAP_PROP_FPS))
        frame_count = float(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        if source_fps <= 0 or frame_count <= 0:
            raise VideoDecodeError(f"video has invalid FPS or frame count: {video_path}")
        duration_ms = min(frame_count / source_fps * 1000, window_seconds * 1000)
        for timestamp_ms in np.arange(0.0, duration_ms, 1000 / sample_fps):
            capture.set(cv2.CAP_PROP_POS_MSEC, float(timestamp_ms))
            ok, bgr = capture.read()
            if not ok:
                continue
            rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
            yield VideoFrame(round(float(timestamp_ms)), rgb)
    finally:
        capture.release()


def extract_clip(
    video_path: Path,
    landmarker: FrameLandmarker,
    *,
    frame_source: FrameSource = iter_sampled_frames,
) -> NDArray[np.float32]:
    timed: list[TimedFeatures] = []
    for frame in frame_source(video_path):
        result = landmarker.detect(frame.rgb, frame.timestamp_ms)
        values = (
            None
            if result is None
            else extract_frame_features(result.landmarks, result.transform, result.blendshapes)
        )
        timed.append(TimedFeatures(frame.timestamp_ms / 1000, values))
    return aggregate_segments(timed)


def _source_fingerprint(path: Path) -> str:
    stat = path.stat()
    return f"{stat.st_size}:{stat.st_mtime_ns}"


def _cached_clip(
    output_root: Path, record: ClipRecord, fingerprint: str
) -> IncludedClip | None:
    feature_path = output_root / SCHEMA_NAME / record.split / f"{record.clip_id}.npz"
    if not feature_path.is_file():
        return None
    try:
        with np.load(feature_path, allow_pickle=False) as cache:
            if cache["schema"].item() != SCHEMA_NAME:
                return None
            if cache["source_fingerprint"].item() != fingerprint:
                return None
            if cache["tokens"].shape != (20, 98):
                return None
    except (OSError, ValueError, KeyError):
        return None
    return IncludedClip(
        record.clip_id, record.split, record.label_index, feature_path, fingerprint
    )


def _save_tokens(
    output_root: Path,
    record: ClipRecord,
    tokens: NDArray[np.float32],
    fingerprint: str,
) -> IncludedClip:
    directory = output_root / SCHEMA_NAME / record.split
    directory.mkdir(parents=True, exist_ok=True)
    feature_path = directory / f"{record.clip_id}.npz"
    temporary = directory / f".{record.clip_id}.npz.tmp"
    with temporary.open("wb") as file:
        np.savez_compressed(
            file,
            tokens=tokens,
            label_index=np.int64(record.label_index),
            schema=np.asarray(SCHEMA_NAME),
            source_fingerprint=np.asarray(fingerprint),
        )
    temporary.replace(feature_path)
    return IncludedClip(
        record.clip_id, record.split, record.label_index, feature_path, fingerprint
    )


def _write_manifest(output_root: Path, manifest: ExtractionManifest) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    path = output_root / "manifest.json"
    temporary = output_root / ".manifest.json.tmp"
    temporary.write_text(
        json.dumps(manifest.to_json_dict(output_root), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    temporary.replace(path)


def extract_contract(
    contract: DatasetContract,
    landmarker: FrameLandmarker,
    output_root: Path,
    *,
    max_excluded_fraction: float = 0.05,
    frame_source: FrameSource = iter_sampled_frames,
) -> ExtractionManifest:
    """Extract every listed clip, persist successes, and report all failures."""
    included: list[IncludedClip] = []
    excluded: list[ExcludedClip] = []
    records = tuple(record for split in contract.splits.values() for record in split)
    for record in records:
        fingerprint = _source_fingerprint(record.video_path)
        cached = _cached_clip(output_root, record, fingerprint)
        if cached is not None:
            included.append(cached)
            continue
        try:
            tokens = extract_clip(record.video_path, landmarker, frame_source=frame_source)
            included.append(_save_tokens(output_root, record, tokens, fingerprint))
        except (
            InvalidFrameFeaturesError,
            InsufficientFaceCoverageError,
            VideoDecodeError,
            OSError,
        ) as error:
            excluded.append(ExcludedClip(record.clip_id, record.split, str(error)))

    manifest = ExtractionManifest(SCHEMA_NAME, tuple(included), tuple(excluded))
    _write_manifest(output_root, manifest)
    fraction = len(excluded) / len(records) if records else 0.0
    if fraction > max_excluded_fraction:
        raise ExtractionThresholdError(manifest, fraction)
    return manifest


__all__ = [
    "ExcludedClip",
    "ExtractionManifest",
    "ExtractionThresholdError",
    "FrameLandmarker",
    "FrameResult",
    "IncludedClip",
    "MediaPipeFaceLandmarker",
    "VideoDecodeError",
    "VideoFrame",
    "extract_clip",
    "extract_contract",
    "iter_sampled_frames",
]
