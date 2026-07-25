from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from zani_ai.engagement.features import RAW_FEATURE_COUNT, TOKEN_FEATURE_COUNT


class InsufficientFaceCoverageError(ValueError):
    """Raised when a temporal segment lacks enough detected face frames."""


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
    raw_feature_count: int = RAW_FEATURE_COUNT,
    token_feature_count: int = TOKEN_FEATURE_COUNT,
) -> NDArray[np.float32]:
    """Aggregate raw frame features into per-segment mean and population std tokens."""
    if window_seconds <= 0 or segment_count <= 0 or minimum_valid_frames <= 0:
        raise ValueError("window, segment count, and minimum frames must be positive")
    segment_seconds = window_seconds / segment_count
    buckets: list[list[NDArray[np.float32]]] = [[] for _ in range(segment_count)]
    for frame in frames:
        if frame.values is None or not 0 <= frame.timestamp_seconds < window_seconds:
            continue
        if frame.values.shape != (raw_feature_count,) or not np.isfinite(frame.values).all():
            raise ValueError(f"frame features must have shape ({raw_feature_count},) and be finite")
        index = min(int(frame.timestamp_seconds / segment_seconds), segment_count - 1)
        buckets[index].append(frame.values)

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


__all__ = [
    "InsufficientFaceCoverageError",
    "TimedFeatures",
    "aggregate_segments",
]
