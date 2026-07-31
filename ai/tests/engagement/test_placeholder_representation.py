from __future__ import annotations

import numpy as np

from zani_ai.engagement.features import get_schema
from zani_ai.engagement.raw_cache import RawClip
from zani_ai.engagement.representations import ZeroPlaceholderTokenRepresentation


def test_placeholder_representation_turns_invalid_mask_entries_into_zero_features() -> None:
    frame_count = 100
    valid_mask = np.ones(frame_count, dtype=np.bool_)
    valid_mask[0] = False
    clip = RawClip(
        landmarks=np.zeros((frame_count, 478, 3), dtype=np.float32),
        transform=np.repeat(np.eye(4, dtype=np.float32)[None, :, :], frame_count, axis=0),
        blendshapes=np.zeros((frame_count, 52), dtype=np.float32),
        timestamps_ms=np.arange(0, 10_000, 100, dtype=np.int32),
        valid_mask=valid_mask,
    )

    tokens = ZeroPlaceholderTokenRepresentation(
        get_schema("mediapipe_98_placeholder_v1")
    ).build(clip)

    assert tokens.shape == (20, 98)
    np.testing.assert_allclose(tokens[0, 13], 800_000)
    np.testing.assert_allclose(tokens[0, 62], 400_000)
    np.testing.assert_allclose(tokens[1, 13], 1_000_000)
    np.testing.assert_allclose(tokens[1, 62], 0)
