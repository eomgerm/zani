from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from zani_ai.engagement.features import RAW_FEATURE_COUNT, TOKEN_FEATURE_COUNT

EXPECTED_FRAME_COUNT = 100
MINIMUM_VALID_FRAME_RATIO = 0.7


class InsufficientFaceCoverageError(ValueError):
    """Raised when a temporal segment lacks enough detected face frames."""


class InsufficientTotalFaceCoverageError(InsufficientFaceCoverageError):
    """Raised when a window has fewer valid frames than the runtime gate."""


@dataclass(frozen=True, slots=True)
class TimedFeatures:
    timestamp_seconds: float
    values: NDArray[np.float32] | None


def aggregate_segments(
    frames: Sequence[TimedFeatures],
    *,
    window_seconds: float = 10.0,
    segment_count: int = 20,
    minimum_valid_frames: int = 3,
    expected_frame_count: int = EXPECTED_FRAME_COUNT,
    minimum_valid_frame_ratio: float = MINIMUM_VALID_FRAME_RATIO,
    raw_feature_count: int = RAW_FEATURE_COUNT,
    token_feature_count: int = TOKEN_FEATURE_COUNT,
) -> NDArray[np.float32]:
    """Aggregate raw frame features into per-segment mean and population std tokens."""
    if (
        window_seconds <= 0
        or segment_count <= 0
        or minimum_valid_frames <= 0
        or expected_frame_count <= 0
        or not 0 < minimum_valid_frame_ratio <= 1
    ):
        raise ValueError("window, segment and expected counts, and coverage must be positive")
    segment_seconds = window_seconds / segment_count
    buckets: list[list[NDArray[np.float32]]] = [[] for _ in range(segment_count)]
    for frame in frames:
        if frame.values is None or not 0 <= frame.timestamp_seconds < window_seconds:
            continue
        if frame.values.shape != (raw_feature_count,) or not np.isfinite(frame.values).all():
            raise ValueError(f"frame features must have shape ({raw_feature_count},) and be finite")
        index = min(int(frame.timestamp_seconds / segment_seconds), segment_count - 1)
        buckets[index].append(frame.values)

    valid_frame_count = sum(map(len, buckets))
    minimum_valid_frame_count = math.ceil(expected_frame_count * minimum_valid_frame_ratio)
    if valid_frame_count < minimum_valid_frame_count:
        raise InsufficientTotalFaceCoverageError(
            f"window has {valid_frame_count} valid frames; {minimum_valid_frame_count} required"
        )

    for index, bucket in enumerate(buckets):
        if len(bucket) < minimum_valid_frames:
            raise InsufficientFaceCoverageError(
                f"segment {index} has {len(bucket)} valid frames; {minimum_valid_frames} required"
            )

    tokens = []
    for bucket in buckets:
        values = np.stack(bucket)
        tokens.append(np.concatenate((values.mean(axis=0), values.std(axis=0))))
    result = np.asarray(tokens, dtype=np.float32)
    expected_shape = (segment_count, token_feature_count)
    if result.shape != expected_shape:
        raise RuntimeError(f"expected token shape {expected_shape}, got {result.shape}")
    return result


def aggregate_segments_with_zero_placeholders(
    frames: Sequence[TimedFeatures],
    *,
    window_seconds: float = 10.0,
    segment_count: int = 20,
    minimum_valid_frames: int = 3,
    expected_frame_count: int = EXPECTED_FRAME_COUNT,
    minimum_valid_frame_ratio: float = MINIMUM_VALID_FRAME_RATIO,
    raw_feature_count: int = RAW_FEATURE_COUNT,
    token_feature_count: int = TOKEN_FEATURE_COUNT,
) -> NDArray[np.float32]:
    """Aggregate fixed frame slots while representing detection failures as zeros.

    The ordinary aggregation runs first to preserve its existing total and
    per-segment face-coverage gate. Only clips that pass that gate reach the
    second pass, where each timestamped missing-face slot becomes a zero raw
    feature vector instead of disappearing from the mean and population std.
    """
    def aggregate(candidate_frames: Sequence[TimedFeatures]) -> NDArray[np.float32]:
        return aggregate_segments(
            candidate_frames,
            window_seconds=window_seconds,
            segment_count=segment_count,
            minimum_valid_frames=minimum_valid_frames,
            expected_frame_count=expected_frame_count,
            minimum_valid_frame_ratio=minimum_valid_frame_ratio,
            raw_feature_count=raw_feature_count,
            token_feature_count=token_feature_count,
        )

    aggregate(frames)
    placeholder = np.zeros(raw_feature_count, dtype=np.float32)
    fixed_slots = tuple(
        TimedFeatures(
            frame.timestamp_seconds,
            placeholder if frame.values is None else frame.values,
        )
        for frame in frames
    )
    return aggregate(fixed_slots)


__all__ = [
    "EXPECTED_FRAME_COUNT",
    "MINIMUM_VALID_FRAME_RATIO",
    "InsufficientFaceCoverageError",
    "InsufficientTotalFaceCoverageError",
    "TimedFeatures",
    "aggregate_segments",
    "aggregate_segments_with_zero_placeholders",
]
