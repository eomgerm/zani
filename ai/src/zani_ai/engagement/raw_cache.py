"""Raw MediaPipe per-frame output cache for EngageNet clips.

`extraction.py` samples video frames with MediaPipe FaceLandmarker and
aggregates them directly into 98D token `.npz` files, discarding the raw
per-frame MediaPipe output. This module runs the same MediaPipe pass once per
clip and persists the raw per-frame landmarks/transform/blendshapes instead,
so multiple downstream feature representations (98D, 132D, and later a raw
landmark-sequence representation) can be derived from one cache without
re-running MediaPipe.

The canonical included-clip set is decided here by `is_clip_included`, which
depends only on per-frame validity and timestamps (never on feature values),
using the exact same 0.5s/20-segment coverage rule as
`segments.aggregate_segments`. Any feature representation built later from
this cache must reuse the same canonical set.

Orchestration (output lock, cache scan, process-pool workers, periodic/
emergency manifest snapshots, threshold check) intentionally mirrors
`extraction.extract_contract_parallel`'s skeleton, per the E0-A plan's Task 3
brief, which explicitly permits reusing that skeleton. Leaf helpers are
imported from `extraction` rather than reimplemented; the only raw-specific
logic here is per-clip raw collection, canonical inclusion, and raw npz
save/cache-check/provenance.

`extraction.py`'s existing public functions (`extract`, `extract_contract`,
`extract_contract_parallel`) are not modified by this module.
"""

from __future__ import annotations

import inspect
import json
import multiprocessing
import sys
import time
from collections.abc import Callable, Iterator, Mapping
from concurrent.futures import Future, ProcessPoolExecutor, as_completed
from contextlib import suppress
from dataclasses import asdict, dataclass
from datetime import UTC, datetime
from hashlib import sha256
from importlib import metadata
from multiprocessing.util import Finalize
from pathlib import Path
from typing import Any

import cv2
import mediapipe as mp
import numpy as np
from numpy.typing import NDArray

from zani_ai.engagement.contracts import ClipRecord, DatasetContract, SplitName
from zani_ai.engagement.extraction import (
    ActiveExtractionError,
    ExcludedClip,
    ExtractionThresholdError,
    FrameLandmarker,
    MediaPipeFaceLandmarker,
    MINIMUM_VALID_FRAMES,
    SAMPLE_FPS,
    SEGMENT_COUNT,
    VideoDecodeError,
    VideoFrame,
    WINDOW_SECONDS,
    _ExtractionOutputLock,
    _expected_exclusion,
    _file_sha256,
    _format_duration,
    _source_fingerprint,
    _unique_temporary_path,
    _validate_unique_records,
    _warn_best_effort,
    iter_sampled_frames,
)
from zani_ai.engagement.features import BLENDSHAPE_NAMES_132, InvalidFrameFeaturesError

type RawFrameSource = Callable[[Path], Iterator[VideoFrame]]

RAW_SCHEMA_NAME = "raw_frames_v1"
RAW_LANDMARK_COUNT = 478
RAW_BLENDSHAPE_COUNT = len(BLENDSHAPE_NAMES_132)
RAW_STORED_KEYS: tuple[str, ...] = (
    "landmarks",
    "transform",
    "blendshapes",
    "valid_mask",
    "timestamps_ms",
)
DEFAULT_RAW_WORKERS = 4
DEFAULT_PROGRESS_EVERY = 25


class InsufficientRawCoverageError(ValueError):
    """Raised when a clip's raw valid-frame coverage fails the canonical rule."""


@dataclass(frozen=True, slots=True)
class RawClip:
    landmarks: NDArray[np.float32]
    transform: NDArray[np.float32]
    blendshapes: NDArray[np.float32]
    timestamps_ms: NDArray[np.int32]
    valid_mask: NDArray[np.bool_]


@dataclass(frozen=True, slots=True)
class RawIncludedClip:
    clip_id: str
    split: SplitName
    feature_path: Path
    source_fingerprint: str


@dataclass(frozen=True, slots=True)
class RawProvenance:
    created_at_utc: str
    mediapipe_version: str
    opencv_version: str
    face_landmarker_model_sha256: str
    face_landmarker_model_size_bytes: int
    sample_fps: float
    window_seconds: float
    segment_count: int
    minimum_valid_frames: int
    raw_landmark_count: int
    blendshape_count: int
    blendshape_names: tuple[str, ...]
    stored: tuple[str, ...]
    worker_count: int
    max_excluded_fraction: float
    algorithm_source_sha256: dict[str, str]
    extraction_fingerprint: str


@dataclass(frozen=True, slots=True)
class RawManifest:
    schema: str
    included: tuple[RawIncludedClip, ...]
    excluded: tuple[ExcludedClip, ...]
    status: str = "complete"
    total_count: int | None = None
    cached_count: int = 0
    provenance: RawProvenance | None = None
    scanned_count: int | None = None

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
            "scanned_count": (
                self.scanned_count
                if self.scanned_count is not None
                else len(self.included) + len(self.excluded)
            ),
            "excluded_fraction": (
                len(self.excluded) / self.total_count if self.total_count else 0.0
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


def is_clip_included(
    valid_mask: NDArray[np.bool_],
    timestamps_ms: NDArray[np.integer[Any]],
    *,
    window_seconds: float = WINDOW_SECONDS,
    segment_count: int = SEGMENT_COUNT,
    minimum_valid_frames: int = MINIMUM_VALID_FRAMES,
) -> bool:
    """Apply the canonical E0 segment-coverage rule to raw validity/timestamps.

    Buckets valid frames into `segment_count` equal-length segments spanning
    `window_seconds` (0.5s/20-segment by default, identical to
    `segments.aggregate_segments`'s bucketing math) and requires every segment
    to contain at least `minimum_valid_frames` valid frames. Depends only on
    `valid_mask`/`timestamps_ms`, never on feature values, so the resulting
    included-clip set is identical across every feature representation built
    from this raw cache.
    """
    if window_seconds <= 0 or segment_count <= 0 or minimum_valid_frames <= 0:
        raise ValueError("window, segment count, and minimum frames must be positive")
    segment_seconds = window_seconds / segment_count
    counts = [0] * segment_count
    for valid, timestamp_ms in zip(valid_mask, timestamps_ms, strict=True):
        if not valid:
            continue
        timestamp_seconds = float(timestamp_ms) / 1000.0
        if not 0 <= timestamp_seconds < window_seconds:
            continue
        index = min(int(timestamp_seconds / segment_seconds), segment_count - 1)
        counts[index] += 1
    return all(count >= minimum_valid_frames for count in counts)


def collect_raw_clip(
    video_path: Path,
    landmarker: FrameLandmarker,
    *,
    frame_source: RawFrameSource = iter_sampled_frames,
) -> RawClip:
    """Sample a clip's frames and collect every raw per-frame MediaPipe output.

    Missing-face frames (`landmarker.detect` returning `None`) are recorded
    with `valid=False` and zero-filled landmarks/transform/blendshapes; the
    timestamp is always recorded so downstream coverage/inclusion logic can
    see the gap. Blendshapes are ordered by `BLENDSHAPE_NAMES_132`.
    """
    landmarks_frames: list[NDArray[np.float32]] = []
    transform_frames: list[NDArray[np.float32]] = []
    blendshape_frames: list[NDArray[np.float32]] = []
    timestamps: list[int] = []
    valid: list[bool] = []
    for frame in frame_source(video_path):
        result = landmarker.detect(frame.rgb, frame.timestamp_ms)
        if result is None:
            landmarks_frames.append(np.zeros((RAW_LANDMARK_COUNT, 3), dtype=np.float32))
            transform_frames.append(np.zeros((4, 4), dtype=np.float32))
            blendshape_frames.append(np.zeros((RAW_BLENDSHAPE_COUNT,), dtype=np.float32))
            valid.append(False)
        else:
            landmarks_frames.append(np.asarray(result.landmarks, dtype=np.float32))
            transform_frames.append(np.asarray(result.transform, dtype=np.float32))
            blendshape_frames.append(
                np.asarray(
                    [result.blendshapes.get(name, 0.0) for name in BLENDSHAPE_NAMES_132],
                    dtype=np.float32,
                )
            )
            valid.append(True)
        timestamps.append(frame.timestamp_ms)

    if timestamps:
        landmarks = np.stack(landmarks_frames).astype(np.float32, copy=False)
        transform = np.stack(transform_frames).astype(np.float32, copy=False)
        blendshapes = np.stack(blendshape_frames).astype(np.float32, copy=False)
    else:
        landmarks = np.zeros((0, RAW_LANDMARK_COUNT, 3), dtype=np.float32)
        transform = np.zeros((0, 4, 4), dtype=np.float32)
        blendshapes = np.zeros((0, RAW_BLENDSHAPE_COUNT), dtype=np.float32)
    return RawClip(
        landmarks=landmarks,
        transform=transform,
        blendshapes=blendshapes,
        timestamps_ms=np.asarray(timestamps, dtype=np.int32),
        valid_mask=np.asarray(valid, dtype=np.bool_),
    )


def _process_raw_clip(
    video_path: Path,
    landmarker: FrameLandmarker,
    *,
    frame_source: RawFrameSource = iter_sampled_frames,
) -> RawClip:
    """Collect raw frames and enforce canonical inclusion, raising on failure."""
    clip = collect_raw_clip(video_path, landmarker, frame_source=frame_source)
    if not is_clip_included(clip.valid_mask, clip.timestamps_ms):
        segment_seconds = WINDOW_SECONDS / SEGMENT_COUNT
        raise InsufficientRawCoverageError(
            f"raw coverage failed canonical rule: each of {SEGMENT_COUNT} "
            f"{segment_seconds:.1f}s segments requires >= {MINIMUM_VALID_FRAMES} "
            "valid frames"
        )
    return clip


def _cached_raw_clip(
    raw_root: Path,
    record: ClipRecord,
    fingerprint: str,
    extraction_fingerprint: str | None = None,
) -> RawIncludedClip | None:
    feature_path = raw_root / record.split / f"{record.clip_id}.npz"
    if not feature_path.is_file():
        return None
    try:
        with np.load(feature_path, allow_pickle=False) as cache:
            if cache["schema"].item() != RAW_SCHEMA_NAME:
                return None
            if cache["source_fingerprint"].item() != fingerprint:
                return None
            if extraction_fingerprint is not None and (
                "extraction_fingerprint" not in cache.files
                or cache["extraction_fingerprint"].item() != extraction_fingerprint
            ):
                return None
            if not all(key in cache.files for key in RAW_STORED_KEYS):
                return None
            timestamps_ms = np.asarray(cache["timestamps_ms"])
            frame_count = timestamps_ms.shape[0]
            if frame_count == 0:
                return None
            landmarks = np.asarray(cache["landmarks"])
            transform = np.asarray(cache["transform"])
            blendshapes = np.asarray(cache["blendshapes"])
            valid_mask = np.asarray(cache["valid_mask"])
            if landmarks.shape != (frame_count, RAW_LANDMARK_COUNT, 3):
                return None
            if transform.shape != (frame_count, 4, 4):
                return None
            if blendshapes.shape != (frame_count, RAW_BLENDSHAPE_COUNT):
                return None
            if valid_mask.shape != (frame_count,):
                return None
            if not (
                np.isfinite(landmarks).all()
                and np.isfinite(transform).all()
                and np.isfinite(blendshapes).all()
            ):
                return None
    except (OSError, ValueError, KeyError):
        return None
    return RawIncludedClip(record.clip_id, record.split, feature_path, fingerprint)


def _save_raw_clip(
    raw_root: Path,
    record: ClipRecord,
    clip: RawClip,
    fingerprint: str,
    extraction_fingerprint: str,
) -> RawIncludedClip:
    directory = raw_root / record.split
    directory.mkdir(parents=True, exist_ok=True)
    feature_path = directory / f"{record.clip_id}.npz"
    temporary = _unique_temporary_path(feature_path)
    try:
        with temporary.open("wb") as file:
            np.savez_compressed(
                file,
                landmarks=clip.landmarks,
                transform=clip.transform,
                blendshapes=clip.blendshapes,
                valid_mask=clip.valid_mask,
                timestamps_ms=clip.timestamps_ms,
                schema=np.asarray(RAW_SCHEMA_NAME),
                source_fingerprint=np.asarray(fingerprint),
                extraction_fingerprint=np.asarray(extraction_fingerprint),
            )
        temporary.replace(feature_path)
    finally:
        temporary.unlink(missing_ok=True)
    return RawIncludedClip(record.clip_id, record.split, feature_path, fingerprint)


def _write_raw_manifest(raw_root: Path, manifest: RawManifest) -> None:
    raw_root.mkdir(parents=True, exist_ok=True)
    path = raw_root / "manifest.json"
    temporary = _unique_temporary_path(path)
    try:
        temporary.write_text(
            json.dumps(manifest.to_json_dict(raw_root), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _package_version(distribution: str, module: object) -> str:
    try:
        return metadata.version(distribution)
    except metadata.PackageNotFoundError:
        version = getattr(module, "__version__", None)
        if not isinstance(version, str) or not version:
            raise RuntimeError(f"could not determine {distribution} package version") from None
        return version


def _source_sha256(value: Callable[..., object] | type[object], name: str) -> str:
    try:
        source = inspect.getsource(value)
    except (OSError, TypeError) as error:
        raise RuntimeError(f"could not read {name} source for raw provenance") from error
    return sha256(source.encode("utf-8")).hexdigest()


def _build_raw_provenance(
    model_asset_path: Path,
    *,
    workers: int,
    max_excluded_fraction: float,
) -> RawProvenance:
    model_sha256 = _file_sha256(model_asset_path)
    model_size = model_asset_path.stat().st_size
    mediapipe_version = _package_version("mediapipe", mp)
    opencv_version = str(cv2.__version__)
    if not opencv_version:
        raise RuntimeError("could not determine OpenCV package version")
    algorithm_source_sha256 = {
        "face_landmarker_adapter": _source_sha256(
            MediaPipeFaceLandmarker, "Face Landmarker adapter"
        ),
        "timestamp_sampler": _source_sha256(iter_sampled_frames, "timestamp sampler"),
        "raw_collection_pipeline": _source_sha256(
            collect_raw_clip, "raw frame collection pipeline"
        ),
        "inclusion_rule": _source_sha256(is_clip_included, "canonical inclusion rule"),
    }
    fingerprint_payload = {
        "schema": RAW_SCHEMA_NAME,
        "mediapipe_version": mediapipe_version,
        "opencv_version": opencv_version,
        "face_landmarker_model_sha256": model_sha256,
        "face_landmarker_model_size_bytes": model_size,
        "sample_fps": SAMPLE_FPS,
        "window_seconds": WINDOW_SECONDS,
        "segment_count": SEGMENT_COUNT,
        "minimum_valid_frames": MINIMUM_VALID_FRAMES,
        "raw_landmark_count": RAW_LANDMARK_COUNT,
        "blendshape_count": RAW_BLENDSHAPE_COUNT,
        "blendshape_names": list(BLENDSHAPE_NAMES_132),
        "stored": list(RAW_STORED_KEYS),
        "landmarker_options": {
            "running_mode": "VIDEO",
            "num_faces": 1,
            "output_face_blendshapes": True,
            "output_facial_transformation_matrixes": True,
        },
        "algorithm_source_sha256": algorithm_source_sha256,
    }
    encoded = json.dumps(fingerprint_payload, sort_keys=True, separators=(",", ":")).encode(
        "utf-8"
    )
    return RawProvenance(
        created_at_utc=datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        mediapipe_version=mediapipe_version,
        opencv_version=opencv_version,
        face_landmarker_model_sha256=model_sha256,
        face_landmarker_model_size_bytes=model_size,
        sample_fps=SAMPLE_FPS,
        window_seconds=WINDOW_SECONDS,
        segment_count=SEGMENT_COUNT,
        minimum_valid_frames=MINIMUM_VALID_FRAMES,
        raw_landmark_count=RAW_LANDMARK_COUNT,
        blendshape_count=RAW_BLENDSHAPE_COUNT,
        blendshape_names=BLENDSHAPE_NAMES_132,
        stored=RAW_STORED_KEYS,
        worker_count=workers,
        max_excluded_fraction=max_excluded_fraction,
        algorithm_source_sha256=algorithm_source_sha256,
        extraction_fingerprint=sha256(encoded).hexdigest(),
    )


@dataclass(frozen=True, slots=True)
class _RawWorkerTask:
    record: ClipRecord
    raw_root: Path
    source_fingerprint: str
    extraction_fingerprint: str


_raw_worker_landmarker: FrameLandmarker | None = None


def _initialize_raw_worker(
    model_asset_path: str, expected_sha256: str, expected_size_bytes: int
) -> None:
    global _raw_worker_landmarker
    model_path = Path(model_asset_path)
    if (
        model_path.stat().st_size != expected_size_bytes
        or _file_sha256(model_path) != expected_sha256
    ):
        raise RuntimeError("Face Landmarker model changed after provenance was recorded")
    landmarker = MediaPipeFaceLandmarker(model_path)
    _raw_worker_landmarker = landmarker
    Finalize(None, landmarker.close, exitpriority=10)


def _extract_raw_worker(task: _RawWorkerTask) -> RawIncludedClip | ExcludedClip:
    if _raw_worker_landmarker is None:
        raise RuntimeError("Face Landmarker worker was not initialized")
    record = task.record
    if _source_fingerprint(record.video_path) != task.source_fingerprint:
        raise OSError("source video changed before extraction started")
    try:
        clip = _process_raw_clip(record.video_path, _raw_worker_landmarker)
    except (
        InvalidFrameFeaturesError,
        InsufficientRawCoverageError,
        VideoDecodeError,
        cv2.error,
    ) as error:
        return _expected_exclusion(record, error)
    if _source_fingerprint(record.video_path) != task.source_fingerprint:
        raise OSError("source video changed during extraction")
    return _save_raw_clip(
        task.raw_root,
        record,
        clip,
        task.source_fingerprint,
        task.extraction_fingerprint,
    )


def extract_raw_contract_parallel(
    contract: DatasetContract,
    model_asset_path: Path,
    output_root: Path,
    *,
    workers: int = DEFAULT_RAW_WORKERS,
    progress_every: int = DEFAULT_PROGRESS_EVERY,
    max_excluded_fraction: float = 0.05,
) -> RawManifest:
    """Extract and cache raw per-frame MediaPipe output for a whole contract.

    Raw npz files and `manifest.json` are written under
    `output_root / RAW_SCHEMA_NAME /`, isolated from the frozen 98D layout
    (`output_root/mediapipe_98_v1/`, `output_root/manifest.json`) so this can
    run against the same `output_root` used for E0 without touching E0
    artifacts. The output lock is likewise scoped to the raw subdirectory, so
    a concurrent 98D `extract_contract_parallel` run against the same
    `output_root` is not blocked by (and does not block) this function.

    Mirrors `extraction.extract_contract_parallel`'s scan/lock/process-pool/
    snapshot orchestration; the only raw-specific logic is per-clip raw
    collection, canonical inclusion, and raw npz save/cache-check.
    """
    if workers <= 0:
        raise ValueError("workers must be positive")
    if progress_every <= 0:
        raise ValueError("progress-every must be positive")
    if not 0 <= max_excluded_fraction <= 1:
        raise ValueError("max-excluded-fraction must be between 0 and 1")
    if not model_asset_path.is_file():
        raise FileNotFoundError(f"Face Landmarker model not found: {model_asset_path}")

    raw_root = output_root / RAW_SCHEMA_NAME
    records = tuple(record for split in contract.splits.values() for record in split)
    _validate_unique_records(records)
    provenance = _build_raw_provenance(
        model_asset_path,
        workers=workers,
        max_excluded_fraction=max_excluded_fraction,
    )
    total = len(records)
    included: dict[tuple[SplitName, str], RawIncludedClip] = {}
    excluded: dict[tuple[SplitName, str], ExcludedClip] = {}
    pending: list[_RawWorkerTask] = []
    cached_count = 0
    started = time.monotonic()
    scanned_count = 0
    fresh_processed = 0
    pending_total = 0
    extraction_started: float | None = None
    last_reported_scan = 0
    last_reported_extract = 0

    def ordered_values(
        values: Mapping[tuple[SplitName, str], RawIncludedClip | ExcludedClip],
    ) -> tuple[RawIncludedClip | ExcludedClip, ...]:
        return tuple(
            values[(record.split, record.clip_id)]
            for record in records
            if (record.split, record.clip_id) in values
        )

    def snapshot(status: str) -> RawManifest:
        included_values = ordered_values(included)
        excluded_values = ordered_values(excluded)
        return RawManifest(
            RAW_SCHEMA_NAME,
            tuple(item for item in included_values if isinstance(item, RawIncludedClip)),
            tuple(item for item in excluded_values if isinstance(item, ExcludedClip)),
            status=status,
            total_count=total,
            cached_count=cached_count,
            provenance=provenance,
            scanned_count=scanned_count,
        )

    def report(
        phase: str,
        status: str = "in_progress",
        *,
        force: bool = False,
    ) -> RawManifest:
        nonlocal last_reported_extract, last_reported_scan
        manifest = snapshot(status)
        processed = len(manifest.included) + len(manifest.excluded)
        if not force:
            if phase == "cache_scan":
                if scanned_count - last_reported_scan < progress_every:
                    return manifest
            elif fresh_processed - last_reported_extract < progress_every:
                return manifest
        _write_raw_manifest(raw_root, manifest)
        elapsed = time.monotonic() - started
        extraction_elapsed = (
            time.monotonic() - extraction_started if extraction_started is not None else 0.0
        )
        rate = fresh_processed / extraction_elapsed if extraction_elapsed > 0 else 0.0
        if phase == "cache_scan":
            eta = None
        elif pending_total == fresh_processed:
            eta = 0.0
        else:
            eta = (pending_total - fresh_processed) / rate if rate > 0 else None
        print(
            "Raw extraction progress | "
            f"phase={phase} scanned={scanned_count}/{total} "
            f"processed={processed}/{total} cached={cached_count} "
            f"included={len(manifest.included)} excluded={len(manifest.excluded)} "
            f"elapsed={_format_duration(elapsed)} clips/sec={rate:.3f} "
            f"ETA={_format_duration(eta)} status={status}",
            flush=True,
        )
        if phase == "cache_scan":
            last_reported_scan = scanned_count
        else:
            last_reported_extract = fresh_processed
        return manifest

    def run_locked() -> RawManifest:
        nonlocal cached_count, extraction_started, fresh_processed, pending_total, scanned_count
        executor: ProcessPoolExecutor | None = None
        failed = False
        phase = "cache_scan"
        try:
            _write_raw_manifest(raw_root, snapshot("in_progress"))
            for record in records:
                key = (record.split, record.clip_id)
                source_fingerprint = _source_fingerprint(record.video_path)
                cached = _cached_raw_clip(
                    raw_root,
                    record,
                    source_fingerprint,
                    provenance.extraction_fingerprint,
                )
                if cached is not None:
                    included[key] = cached
                    cached_count += 1
                else:
                    pending.append(
                        _RawWorkerTask(
                            record,
                            raw_root,
                            source_fingerprint,
                            provenance.extraction_fingerprint,
                        )
                    )
                scanned_count += 1
                report("cache_scan")

            report("cache_scan", force=True)
            pending_total = len(pending)
            extraction_started = time.monotonic()
            phase = "extract"
            if pending:
                executor = ProcessPoolExecutor(
                    max_workers=workers,
                    mp_context=multiprocessing.get_context("spawn"),
                    initializer=_initialize_raw_worker,
                    initargs=(
                        str(model_asset_path),
                        provenance.face_landmarker_model_sha256,
                        provenance.face_landmarker_model_size_bytes,
                    ),
                )
                futures: dict[Future[RawIncludedClip | ExcludedClip], _RawWorkerTask] = {
                    executor.submit(_extract_raw_worker, task): task for task in pending
                }
                for future in as_completed(futures):
                    result = future.result()
                    key = (result.split, result.clip_id)
                    if isinstance(result, RawIncludedClip):
                        included[key] = result
                    else:
                        excluded[key] = result
                    fresh_processed += 1
                    report("extract")
        except BaseException:
            failed = True
            try:
                report(phase, force=True)
            except BaseException as report_error:
                _warn_best_effort(
                    f"Emergency raw extraction manifest update failed: {report_error}"
                )
            raise
        finally:
            if executor is not None:
                try:
                    executor.shutdown(wait=True, cancel_futures=failed)
                except BaseException as shutdown_error:
                    with suppress(BaseException):
                        executor.shutdown(wait=False, cancel_futures=True)
                    if failed:
                        _warn_best_effort(
                            f"Raw extraction worker shutdown also failed: {shutdown_error}"
                        )
                    else:
                        try:
                            report(phase, force=True)
                        except BaseException as report_error:
                            _warn_best_effort(
                                "Emergency raw extraction manifest update failed after "
                                f"worker shutdown error: {report_error}"
                            )
                        raise

        fraction = len(excluded) / total if total else 0.0
        status = (
            "complete" if fraction <= max_excluded_fraction else "exclusion_threshold_exceeded"
        )
        manifest = report("complete", status, force=True)
        if fraction > max_excluded_fraction:
            raise ExtractionThresholdError(manifest, fraction, max_excluded_fraction)
        return manifest

    output_lock = _ExtractionOutputLock(raw_root)
    output_lock.acquire()
    try:
        return run_locked()
    finally:
        output_lock.release(suppress_errors=sys.exception() is not None)


__all__ = [
    "DEFAULT_RAW_WORKERS",
    "RAW_BLENDSHAPE_COUNT",
    "RAW_LANDMARK_COUNT",
    "RAW_SCHEMA_NAME",
    "RAW_STORED_KEYS",
    "InsufficientRawCoverageError",
    "RawClip",
    "RawIncludedClip",
    "RawManifest",
    "RawProvenance",
    "ActiveExtractionError",
    "ExtractionThresholdError",
    "VideoDecodeError",
    "collect_raw_clip",
    "extract_raw_contract_parallel",
    "is_clip_included",
]
