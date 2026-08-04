"""Sampling rate is configurable, and 10 FPS keeps its existing identity.

The rate used to be a module constant, so the raw cache and the landmark
sequence derived from it were both fixed at 100 steps. The paper this pipeline
reproduces feeds 300 frames at 30 FPS. Making the rate a parameter must not
disturb the 10 FPS caches and checkpoints already on disk.
"""

from __future__ import annotations

import numpy as np
import pytest

from zani_ai.engagement.extraction import SAMPLE_FPS, WINDOW_SECONDS
from zani_ai.engagement.raw_cache import RAW_SCHEMA_NAME, RawClip, raw_schema_name
from zani_ai.engagement.representations import (
    LANDMARK_SEQUENCE_NAME,
    LandmarkSequenceRepresentation,
    ZeroPlaceholderLandmarkSequenceRepresentation,
    landmark_sequence_name,
    landmark_sequence_placeholder_name,
)


def test_ten_fps_keeps_the_original_schema_names() -> None:
    """Renaming these would orphan every cache and checkpoint already built."""
    assert raw_schema_name(SAMPLE_FPS) == RAW_SCHEMA_NAME == "raw_frames_v1"
    assert landmark_sequence_name(100) == LANDMARK_SEQUENCE_NAME == "landmark_78_v1"


def test_other_rates_get_their_own_schema() -> None:
    """Separate names mean separate directories, so rates cannot mix."""
    assert raw_schema_name(30.0) == "raw_frames_30fps_v1"
    assert landmark_sequence_name(300) == "landmark_78_300_v1"


@pytest.mark.parametrize(
    ("sample_fps", "expected_name", "expected_steps"),
    [(10.0, "landmark_78_v1", 100), (30.0, "landmark_78_300_v1", 300)],
)
def test_representation_follows_the_rate(
    sample_fps: float, expected_name: str, expected_steps: int
) -> None:
    representation = LandmarkSequenceRepresentation.for_sample_fps(sample_fps)

    assert representation.name == expected_name
    assert representation.output_shape == (3, expected_steps, 78)
    assert representation.sample_fps == sample_fps


def _clip(sample_fps: float, steps: int) -> RawClip:
    """A clip whose frame i carries the constant value i, so steps are traceable."""
    timestamps = np.array(
        [round(step * 1000 / sample_fps) for step in range(steps)], dtype=np.int32
    )
    landmarks = np.zeros((steps, 478, 3), dtype=np.float32)
    for step in range(steps):
        landmarks[step] = float(step)
    return RawClip(
        landmarks=landmarks,
        transform=np.zeros((steps, 4, 4), dtype=np.float32),
        blendshapes=np.zeros((steps, 52), dtype=np.float32),
        timestamps_ms=timestamps,
        valid_mask=np.ones(steps, dtype=np.bool_),
    )


@pytest.mark.parametrize(("sample_fps", "steps"), [(10.0, 100), (30.0, 300)])
def test_every_grid_step_takes_its_own_frame(sample_fps: float, steps: int) -> None:
    """Guards the grid against drift.

    The step index used to come from dividing by a rounded millisecond stride.
    At 10 FPS that stride is exactly 100ms so nothing showed, but 1000/30
    rounds to 33 and the error accumulates until step 299 maps to 302 -- past
    the end of the grid, silently dropping the tail of every clip.
    """
    sequence = LandmarkSequenceRepresentation.for_sample_fps(sample_fps).build(
        _clip(sample_fps, steps)
    )

    # Channel 0, node 0 carries the source frame index the step drew from.
    np.testing.assert_array_equal(sequence[0, :, 0], np.arange(steps, dtype=np.float32))


def test_step_count_follows_the_window() -> None:
    assert LandmarkSequenceRepresentation.for_sample_fps(
        30.0, window_seconds=WINDOW_SECONDS
    ).output_shape[1] == round(WINDOW_SECONDS * 30.0)


def test_missing_frames_are_forward_filled() -> None:
    """A dropped frame must not shift later steps onto the wrong index."""
    clip = _clip(30.0, 300)
    clip.valid_mask[5] = False

    sequence = LandmarkSequenceRepresentation.for_sample_fps(30.0).build(clip)

    assert sequence[0, 4, 0] == 4.0
    assert sequence[0, 5, 0] == 4.0  # forward-filled from the last valid step
    assert sequence[0, 6, 0] == 6.0  # and the grid resumes, not shifted


def _gapped_clip(sample_fps: float, steps: int, valid_steps: set[int]) -> RawClip:
    """Frame i carries value i+1, and only `valid_steps` were detected."""
    timestamps = np.array(
        [round(step * 1000 / sample_fps) for step in range(steps)], dtype=np.int32
    )
    landmarks = np.zeros((steps, 478, 3), dtype=np.float32)
    for step in range(steps):
        landmarks[step] = float(step + 1)
    return RawClip(
        landmarks=landmarks,
        transform=np.zeros((steps, 4, 4), dtype=np.float32),
        blendshapes=np.zeros((steps, 52), dtype=np.float32),
        timestamps_ms=timestamps,
        valid_mask=np.array([step in valid_steps for step in range(steps)], dtype=np.bool_),
    )


def test_placeholder_sequence_leaves_gaps_at_zero() -> None:
    """Forward-filling would report a held-still face where there was no face.

    arXiv:2403.17175 §5 classifies absent-face samples as Not-Engaged, so the
    gap is the signal. Filling it erases what the paper learns from.
    """
    clip = _gapped_clip(30.0, 300, valid_steps={0, 1, 150})
    filled = LandmarkSequenceRepresentation.for_sample_fps(30.0).build(clip)
    zeroed = ZeroPlaceholderLandmarkSequenceRepresentation.for_sample_fps(30.0).build(clip)

    # Step 2 has no face. Forward-fill copies step 1; the placeholder leaves zero.
    assert filled[0, 2, 0] == 2.0
    assert zeroed[0, 2, 0] == 0.0
    # The steps that were detected are identical under both policies.
    for step in (0, 1, 150):
        assert zeroed[0, step, 0] == filled[0, step, 0] == float(step + 1)
    assert int((zeroed[0, :, 0] != 0).sum()) == 3


def test_placeholder_sequence_keeps_a_clip_with_no_face_at_all() -> None:
    """The paper trained on these clips, so they must not be excluded here."""
    clip = _gapped_clip(30.0, 300, valid_steps=set())
    representation = ZeroPlaceholderLandmarkSequenceRepresentation.for_sample_fps(30.0)

    sequence = representation.build(clip)

    assert sequence.shape == representation.output_shape == (3, 300, 78)
    assert not sequence.any()
    with pytest.raises(ValueError, match="no valid frame"):
        LandmarkSequenceRepresentation.for_sample_fps(30.0).build(clip)


def test_placeholder_schema_names_are_separate_caches() -> None:
    """Two policies must not share a directory: the tensors differ."""
    assert landmark_sequence_placeholder_name(300) == "landmark_78_300_placeholder_v1"
    assert landmark_sequence_placeholder_name(100) == "landmark_78_placeholder_v1"
    assert (
        ZeroPlaceholderLandmarkSequenceRepresentation.for_sample_fps(30.0).name
        == "landmark_78_300_placeholder_v1"
    )
