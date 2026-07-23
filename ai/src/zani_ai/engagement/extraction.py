from __future__ import annotations

import json
import multiprocessing
import time
from collections.abc import Callable, Iterator, Mapping
from concurrent.futures import Future, ProcessPoolExecutor, as_completed
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from hashlib import sha256
from importlib import metadata
from multiprocessing.util import Finalize
from pathlib import Path
from typing import Protocol, cast

import cv2
import mediapipe as mp
import numpy as np
from mediapipe.tasks.python import vision
from mediapipe.tasks.python.core.base_options import BaseOptions
from numpy.typing import NDArray

from zani_ai.engagement.contracts import ClipRecord, DatasetContract, SplitName
from zani_ai.engagement.features import (
    BLENDSHAPE_NAMES,
    RAW_FEATURE_COUNT,
    SCHEMA_NAME,
    TOKEN_FEATURE_COUNT,
    InvalidFrameFeaturesError,
    extract_frame_features,
)
from zani_ai.engagement.segments import (
    InsufficientFaceCoverageError,
    TimedFeatures,
    aggregate_segments,
)

type FrameSource = Callable[[Path], Iterator["VideoFrame"]]

SAMPLE_FPS = 10.0
WINDOW_SECONDS = 10.0
SEGMENT_COUNT = 20
DEFAULT_WORKERS = 2
DEFAULT_PROGRESS_EVERY = 25


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
class ExtractionProvenance:
    created_at_utc: str
    mediapipe_version: str
    opencv_version: str
    face_landmarker_model_sha256: str
    face_landmarker_model_size_bytes: int
    sample_fps: float
    window_seconds: float
    segment_count: int
    raw_feature_dimension: int
    token_feature_dimension: int
    feature_schema: str
    gaze_proxy_dimension: int
    head_pose_dimension: int
    blendshape_names: tuple[str, ...]
    aggregation: tuple[str, ...]
    worker_count: int
    max_excluded_fraction: float
    extraction_fingerprint: str


@dataclass(frozen=True, slots=True)
class ExtractionManifest:
    schema: str
    included: tuple[IncludedClip, ...]
    excluded: tuple[ExcludedClip, ...]
    status: str = "complete"
    total_count: int | None = None
    cached_count: int = 0
    provenance: ExtractionProvenance | None = None

    def to_json_dict(self, root: Path) -> dict[str, object]:
        payload: dict[str, object] = {
            "schema": self.schema,
            "status": self.status,
            "complete": self.status == "complete",
            "processed_count": len(self.included) + len(self.excluded),
            "total_count": (
                self.total_count
                if self.total_count is not None
                else len(self.included) + len(self.excluded)
            ),
            "cached_count": self.cached_count,
            "excluded_fraction": (
                len(self.excluded) / self.total_count
                if self.total_count
                else 0.0
            ),
            "included": [
                {
                    **asdict(item),
                    "feature_path": item.feature_path.relative_to(root).as_posix(),
                }
                for item in self.included
            ],
            "excluded": [asdict(item) for item in self.excluded],
        }
        if self.provenance is not None:
            payload["provenance"] = asdict(self.provenance)
        return payload


class ExtractionThresholdError(RuntimeError):
    def __init__(
        self, manifest: ExtractionManifest, fraction: float, maximum_fraction: float = 0.05
    ) -> None:
        self.manifest = manifest
        self.fraction = fraction
        self.maximum_fraction = maximum_fraction
        super().__init__(
            f"excluded {fraction:.1%} of clips; maximum allowed is {maximum_fraction:.1%}; "
            "final manifest and completed clip caches were retained"
        )


def iter_sampled_frames(
    video_path: Path,
    sample_fps: float = SAMPLE_FPS,
    window_seconds: float = WINDOW_SECONDS,
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
            rgb = cast(NDArray[np.uint8], cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
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
    output_root: Path,
    record: ClipRecord,
    fingerprint: str,
    extraction_fingerprint: str | None = None,
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
            if int(cache["label_index"].item()) != record.label_index:
                return None
            tokens = np.asarray(cache["tokens"])
            if tokens.shape != (SEGMENT_COUNT, TOKEN_FEATURE_COUNT):
                return None
            if not np.isfinite(tokens).all():
                return None
            if extraction_fingerprint is not None and (
                "extraction_fingerprint" not in cache.files
                or cache["extraction_fingerprint"].item() != extraction_fingerprint
            ):
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
    extraction_fingerprint: str | None = None,
) -> IncludedClip:
    directory = output_root / SCHEMA_NAME / record.split
    directory.mkdir(parents=True, exist_ok=True)
    feature_path = directory / f"{record.clip_id}.npz"
    temporary = directory / f".{record.clip_id}.npz.tmp"
    try:
        with temporary.open("wb") as file:
            if extraction_fingerprint is None:
                np.savez_compressed(
                    file,
                    tokens=tokens,
                    label_index=np.int64(record.label_index),
                    schema=np.asarray(SCHEMA_NAME),
                    source_fingerprint=np.asarray(fingerprint),
                )
            else:
                np.savez_compressed(
                    file,
                    tokens=tokens,
                    label_index=np.int64(record.label_index),
                    schema=np.asarray(SCHEMA_NAME),
                    source_fingerprint=np.asarray(fingerprint),
                    extraction_fingerprint=np.asarray(extraction_fingerprint),
                )
        temporary.replace(feature_path)
    finally:
        temporary.unlink(missing_ok=True)
    return IncludedClip(
        record.clip_id, record.split, record.label_index, feature_path, fingerprint
    )


def _write_manifest(output_root: Path, manifest: ExtractionManifest) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    path = output_root / "manifest.json"
    temporary = output_root / ".manifest.json.tmp"
    try:
        temporary.write_text(
            json.dumps(manifest.to_json_dict(output_root), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


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

    fraction = len(excluded) / len(records) if records else 0.0
    status = "complete" if fraction <= max_excluded_fraction else "exclusion_threshold_exceeded"
    manifest = ExtractionManifest(
        SCHEMA_NAME,
        tuple(included),
        tuple(excluded),
        status=status,
        total_count=len(records),
    )
    _write_manifest(output_root, manifest)
    if fraction > max_excluded_fraction:
        raise ExtractionThresholdError(manifest, fraction, max_excluded_fraction)
    return manifest


@dataclass(frozen=True, slots=True)
class _WorkerTask:
    record: ClipRecord
    output_root: Path
    source_fingerprint: str
    extraction_fingerprint: str


_worker_landmarker: MediaPipeFaceLandmarker | None = None


def _initialize_worker(
    model_asset_path: str, expected_sha256: str, expected_size_bytes: int
) -> None:
    global _worker_landmarker
    model_path = Path(model_asset_path)
    if (
        model_path.stat().st_size != expected_size_bytes
        or _file_sha256(model_path) != expected_sha256
    ):
        raise RuntimeError("Face Landmarker model changed after provenance was recorded")
    _worker_landmarker = MediaPipeFaceLandmarker(model_path)
    Finalize(None, _worker_landmarker.close, exitpriority=10)


def _extract_worker(task: _WorkerTask) -> IncludedClip | ExcludedClip:
    if _worker_landmarker is None:
        raise RuntimeError("Face Landmarker worker was not initialized")
    record = task.record
    try:
        if _source_fingerprint(record.video_path) != task.source_fingerprint:
            raise OSError("source video changed before extraction started")
        tokens = extract_clip(record.video_path, _worker_landmarker)
        if _source_fingerprint(record.video_path) != task.source_fingerprint:
            raise OSError("source video changed during extraction")
        return _save_tokens(
            task.output_root,
            record,
            tokens,
            task.source_fingerprint,
            task.extraction_fingerprint,
        )
    except Exception as error:
        return ExcludedClip(
            record.clip_id,
            record.split,
            f"{type(error).__name__}: {error}",
        )


def _file_sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _package_version(distribution: str, module: object) -> str:
    try:
        return metadata.version(distribution)
    except metadata.PackageNotFoundError:
        version = getattr(module, "__version__", None)
        if not isinstance(version, str) or not version:
            raise RuntimeError(f"could not determine {distribution} package version") from None
        return version


def _build_provenance(
    model_asset_path: Path,
    *,
    workers: int,
    max_excluded_fraction: float,
) -> ExtractionProvenance:
    model_sha256 = _file_sha256(model_asset_path)
    model_size = model_asset_path.stat().st_size
    mediapipe_version = _package_version("mediapipe", mp)
    opencv_version = str(cv2.__version__)
    if not opencv_version:
        raise RuntimeError("could not determine OpenCV package version")
    fingerprint_payload = {
        "mediapipe_version": mediapipe_version,
        "opencv_version": opencv_version,
        "face_landmarker_model_sha256": model_sha256,
        "face_landmarker_model_size_bytes": model_size,
        "sample_fps": SAMPLE_FPS,
        "window_seconds": WINDOW_SECONDS,
        "segment_count": SEGMENT_COUNT,
        "raw_feature_dimension": RAW_FEATURE_COUNT,
        "token_feature_dimension": TOKEN_FEATURE_COUNT,
        "feature_schema": SCHEMA_NAME,
        "blendshape_names": list(BLENDSHAPE_NAMES),
        "aggregation": ["mean", "population_standard_deviation"],
    }
    encoded = json.dumps(
        fingerprint_payload, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return ExtractionProvenance(
        created_at_utc=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        mediapipe_version=mediapipe_version,
        opencv_version=opencv_version,
        face_landmarker_model_sha256=model_sha256,
        face_landmarker_model_size_bytes=model_size,
        sample_fps=SAMPLE_FPS,
        window_seconds=WINDOW_SECONDS,
        segment_count=SEGMENT_COUNT,
        raw_feature_dimension=RAW_FEATURE_COUNT,
        token_feature_dimension=TOKEN_FEATURE_COUNT,
        feature_schema=SCHEMA_NAME,
        gaze_proxy_dimension=8,
        head_pose_dimension=6,
        blendshape_names=BLENDSHAPE_NAMES,
        aggregation=("mean", "population_standard_deviation"),
        worker_count=workers,
        max_excluded_fraction=max_excluded_fraction,
        extraction_fingerprint=sha256(encoded).hexdigest(),
    )


def _format_duration(seconds: float | None) -> str:
    if seconds is None or not np.isfinite(seconds):
        return "unknown"
    rounded = max(0, round(seconds))
    hours, remainder = divmod(rounded, 3600)
    minutes, secs = divmod(remainder, 60)
    return f"{hours:02d}:{minutes:02d}:{secs:02d}"


def extract_contract_parallel(
    contract: DatasetContract,
    model_asset_path: Path,
    output_root: Path,
    *,
    workers: int = DEFAULT_WORKERS,
    progress_every: int = DEFAULT_PROGRESS_EVERY,
    max_excluded_fraction: float = 0.05,
) -> ExtractionManifest:
    """Extract a contract with isolated process-local Face Landmarker instances."""
    if workers <= 0:
        raise ValueError("workers must be positive")
    if progress_every <= 0:
        raise ValueError("progress-every must be positive")
    if not 0 <= max_excluded_fraction <= 1:
        raise ValueError("max-excluded-fraction must be between 0 and 1")
    if not model_asset_path.is_file():
        raise FileNotFoundError(f"Face Landmarker model not found: {model_asset_path}")

    provenance = _build_provenance(
        model_asset_path,
        workers=workers,
        max_excluded_fraction=max_excluded_fraction,
    )
    records = tuple(record for split in contract.splits.values() for record in split)
    total = len(records)
    included: dict[tuple[SplitName, str], IncludedClip] = {}
    excluded: dict[tuple[SplitName, str], ExcludedClip] = {}
    pending: list[_WorkerTask] = []
    cached_count = 0
    started = time.monotonic()
    last_reported = 0

    def ordered_values(
        values: Mapping[tuple[SplitName, str], IncludedClip | ExcludedClip],
    ) -> tuple[IncludedClip | ExcludedClip, ...]:
        return tuple(
            values[(record.split, record.clip_id)]
            for record in records
            if (record.split, record.clip_id) in values
        )

    def snapshot(status: str) -> ExtractionManifest:
        included_values = ordered_values(included)
        excluded_values = ordered_values(excluded)
        return ExtractionManifest(
            SCHEMA_NAME,
            tuple(item for item in included_values if isinstance(item, IncludedClip)),
            tuple(item for item in excluded_values if isinstance(item, ExcludedClip)),
            status=status,
            total_count=total,
            cached_count=cached_count,
            provenance=provenance,
        )

    def report(status: str = "in_progress", *, force: bool = False) -> ExtractionManifest:
        nonlocal last_reported
        manifest = snapshot(status)
        processed = len(manifest.included) + len(manifest.excluded)
        if not force and processed - last_reported < progress_every:
            return manifest
        _write_manifest(output_root, manifest)
        elapsed = time.monotonic() - started
        rate = processed / elapsed if elapsed > 0 else 0.0
        eta = (total - processed) / rate if rate > 0 else None
        print(
            "Extraction progress | "
            f"processed={processed}/{total} cached={cached_count} "
            f"included={len(manifest.included)} excluded={len(manifest.excluded)} "
            f"elapsed={_format_duration(elapsed)} clips/sec={rate:.3f} "
            f"ETA={_format_duration(eta)} status={status}",
            flush=True,
        )
        last_reported = processed
        return manifest

    _write_manifest(output_root, snapshot("in_progress"))
    for record in records:
        key = (record.split, record.clip_id)
        try:
            source_fingerprint = _source_fingerprint(record.video_path)
        except OSError as error:
            excluded[key] = ExcludedClip(
                record.clip_id,
                record.split,
                f"{type(error).__name__}: {error}",
            )
            continue
        cached = _cached_clip(
            output_root,
            record,
            source_fingerprint,
            provenance.extraction_fingerprint,
        )
        if cached is not None:
            included[key] = cached
            cached_count += 1
        else:
            pending.append(
                _WorkerTask(
                    record,
                    output_root,
                    source_fingerprint,
                    provenance.extraction_fingerprint,
                )
            )

    if included or excluded:
        report(force=True)

    executor: ProcessPoolExecutor | None = None
    try:
        if pending:
            executor = ProcessPoolExecutor(
                max_workers=workers,
                mp_context=multiprocessing.get_context("spawn"),
                initializer=_initialize_worker,
                initargs=(
                    str(model_asset_path),
                    provenance.face_landmarker_model_sha256,
                    provenance.face_landmarker_model_size_bytes,
                ),
            )
            futures: dict[Future[IncludedClip | ExcludedClip], _WorkerTask] = {
                executor.submit(_extract_worker, task): task for task in pending
            }
            for future in as_completed(futures):
                result = future.result()
                key = (result.split, result.clip_id)
                if isinstance(result, IncludedClip):
                    included[key] = result
                else:
                    excluded[key] = result
                report()
            executor.shutdown(wait=True)
            executor = None
    except BaseException:
        report(force=True)
        if executor is not None:
            executor.shutdown(wait=False, cancel_futures=True)
        raise

    fraction = len(excluded) / total if total else 0.0
    status = "complete" if fraction <= max_excluded_fraction else "exclusion_threshold_exceeded"
    manifest = report(status, force=True)
    if fraction > max_excluded_fraction:
        raise ExtractionThresholdError(manifest, fraction, max_excluded_fraction)
    return manifest


__all__ = [
    "ExcludedClip",
    "ExtractionManifest",
    "ExtractionProvenance",
    "ExtractionThresholdError",
    "FrameLandmarker",
    "FrameResult",
    "IncludedClip",
    "MediaPipeFaceLandmarker",
    "VideoDecodeError",
    "VideoFrame",
    "extract_clip",
    "extract_contract",
    "extract_contract_parallel",
    "iter_sampled_frames",
]
