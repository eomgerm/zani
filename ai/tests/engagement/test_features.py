from __future__ import annotations

import numpy as np
import pytest

from zani_ai.engagement.features import (
    BLENDSHAPE_NAMES,
    InvalidFrameFeaturesError,
    extract_frame_features,
)


def _known_landmarks() -> np.ndarray:
    landmarks = np.zeros((478, 3), dtype=np.float32)
    landmarks[33, :2] = (0.1, 0.5)
    landmarks[133, :2] = (0.3, 0.5)
    landmarks[159, :2] = (0.2, 0.4)
    landmarks[145, :2] = (0.2, 0.6)
    landmarks[468:473, :2] = (0.2, 0.55)
    landmarks[263, :2] = (0.9, 0.5)
    landmarks[362, :2] = (0.7, 0.5)
    landmarks[386, :2] = (0.8, 0.4)
    landmarks[374, :2] = (0.8, 0.6)
    landmarks[473:478, :2] = (0.8, 0.45)
    landmarks[1, :2] = (0.4, 0.6)
    return landmarks


def test_extract_frame_features_has_stable_49_value_order() -> None:
    blendshapes = {name: index / 100 for index, name in enumerate(BLENDSHAPE_NAMES)}

    features = extract_frame_features(_known_landmarks(), np.eye(4), blendshapes)

    assert features.shape == (49,)
    np.testing.assert_allclose(features[:8], [0.5, 0.75, 0.5, 0.25, 0.5, 0.5, 0, 0.5], atol=1e-6)
    np.testing.assert_allclose(features[8:14], [0, 0, 0, 0.4, 0.6, 1.25], atol=1e-6)
    np.testing.assert_allclose(features[14:], np.arange(35) / 100, atol=1e-6)


def test_extract_frame_features_defaults_missing_blendshape_to_zero() -> None:
    features = extract_frame_features(_known_landmarks(), np.eye(4), {})

    np.testing.assert_array_equal(features[14:], np.zeros(35))


def test_extract_frame_features_rejects_wrong_landmark_shape() -> None:
    with pytest.raises(InvalidFrameFeaturesError, match="478"):
        extract_frame_features(np.zeros((468, 3)), np.eye(4), {})


def test_extract_frame_features_rejects_non_finite_values() -> None:
    landmarks = _known_landmarks()
    landmarks[1, 0] = np.nan

    with pytest.raises(InvalidFrameFeaturesError, match="finite"):
        extract_frame_features(landmarks, np.eye(4), {})
