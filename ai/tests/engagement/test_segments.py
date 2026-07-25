from __future__ import annotations

import numpy as np
import pytest

from zani_ai.engagement.segments import (
    InsufficientFaceCoverageError,
    TimedFeatures,
    aggregate_segments,
)


def _complete_window() -> list[TimedFeatures]:
    frames: list[TimedFeatures] = []
    for segment in range(20):
        for offset in range(5):
            timestamp = segment * 0.5 + offset * 0.1
            values = np.full(49, segment + offset, dtype=np.float32)
            frames.append(TimedFeatures(timestamp, values))
    return frames


def test_aggregate_segments_returns_mean_then_population_std() -> None:
    tokens = aggregate_segments(_complete_window())

    assert tokens.shape == (20, 98)
    np.testing.assert_allclose(tokens[0, :49], 2)
    np.testing.assert_allclose(tokens[0, 49:], np.sqrt(2))
    np.testing.assert_allclose(tokens[19, :49], 21)


def test_aggregate_segments_ignores_missing_faces_when_three_frames_remain() -> None:
    frames = _complete_window()
    frames[0] = TimedFeatures(frames[0].timestamp_seconds, None)
    frames[1] = TimedFeatures(frames[1].timestamp_seconds, None)

    tokens = aggregate_segments(frames)

    np.testing.assert_allclose(tokens[0, :49], 3)


def test_aggregate_segments_rejects_segment_with_two_valid_frames() -> None:
    frames = _complete_window()
    for index in range(3):
        frames[index] = TimedFeatures(frames[index].timestamp_seconds, None)

    with pytest.raises(InsufficientFaceCoverageError, match="segment 0"):
        aggregate_segments(frames)
