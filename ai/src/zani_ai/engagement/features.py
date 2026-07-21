from __future__ import annotations

from collections.abc import Mapping

import numpy as np
from numpy.typing import NDArray

SCHEMA_NAME = "mediapipe_98_v1"
RAW_FEATURE_COUNT = 49
TOKEN_FEATURE_COUNT = 98

BLENDSHAPE_NAMES = (
    "browDownLeft",
    "browDownRight",
    "browInnerUp",
    "browOuterUpLeft",
    "browOuterUpRight",
    "cheekPuff",
    "cheekSquintLeft",
    "cheekSquintRight",
    "eyeBlinkLeft",
    "eyeBlinkRight",
    "eyeLookDownLeft",
    "eyeLookDownRight",
    "eyeLookInLeft",
    "eyeLookInRight",
    "eyeLookOutLeft",
    "eyeLookOutRight",
    "eyeLookUpLeft",
    "eyeLookUpRight",
    "eyeSquintLeft",
    "eyeSquintRight",
    "eyeWideLeft",
    "eyeWideRight",
    "jawOpen",
    "mouthClose",
    "mouthFrownLeft",
    "mouthFrownRight",
    "mouthFunnel",
    "mouthPucker",
    "mouthSmileLeft",
    "mouthSmileRight",
    "mouthStretchLeft",
    "mouthStretchRight",
    "mouthUpperUpLeft",
    "mouthUpperUpRight",
    "noseSneerLeft",
)

_EPSILON = 1e-6


class InvalidFrameFeaturesError(ValueError):
    """Raised when a MediaPipe frame cannot produce the fixed feature vector."""


def _axis_position(
    point: NDArray[np.floating],
    start: NDArray[np.floating],
    end: NDArray[np.floating],
) -> float:
    axis = end - start
    denominator = max(float(np.dot(axis, axis)), _EPSILON)
    return float(np.dot(point - start, axis) / denominator)


def _eye_position(
    iris: NDArray[np.floating],
    landmarks: NDArray[np.floating],
    outer: int,
    inner: int,
    upper: int,
    lower: int,
) -> NDArray[np.float32]:
    horizontal = _axis_position(iris, landmarks[outer, :2], landmarks[inner, :2])
    vertical = _axis_position(iris, landmarks[upper, :2], landmarks[lower, :2])
    return np.asarray((horizontal, vertical), dtype=np.float32)


def _matrix_to_euler_xyz(rotation: NDArray[np.floating]) -> tuple[float, float, float]:
    """Return yaw, pitch and roll in radians from an XYZ rotation matrix."""
    horizontal = float(np.hypot(rotation[0, 0], rotation[1, 0]))
    singular = horizontal < _EPSILON
    if singular:
        roll = float(np.arctan2(-rotation[1, 2], rotation[1, 1]))
        pitch = float(np.arctan2(-rotation[2, 0], horizontal))
        yaw = 0.0
    else:
        roll = float(np.arctan2(rotation[2, 1], rotation[2, 2]))
        pitch = float(np.arctan2(-rotation[2, 0], horizontal))
        yaw = float(np.arctan2(rotation[1, 0], rotation[0, 0]))
    return yaw, pitch, roll


def extract_frame_features(
    landmarks: NDArray[np.floating],
    transform: NDArray[np.floating],
    blendshapes: Mapping[str, float],
) -> NDArray[np.float32]:
    """Convert one Face Landmarker result to the 49-value raw feature contract."""
    if landmarks.shape != (478, 3):
        raise InvalidFrameFeaturesError(
            f"expected 478 landmarks with xyz coordinates, got {landmarks.shape}"
        )
    if transform.shape != (4, 4):
        raise InvalidFrameFeaturesError(f"expected a 4x4 face transform, got {transform.shape}")

    right_iris = landmarks[468:473, :2].mean(axis=0)
    left_iris = landmarks[473:478, :2].mean(axis=0)
    right = _eye_position(right_iris, landmarks, 33, 133, 159, 145)
    left = _eye_position(left_iris, landmarks, 263, 362, 386, 374)
    gaze = np.concatenate((right, left, (right + left) / 2, right - left))

    yaw, pitch, roll = _matrix_to_euler_xyz(transform[:3, :3])
    interocular = max(float(np.linalg.norm(landmarks[33, :2] - landmarks[263, :2])), _EPSILON)
    head = np.asarray(
        (yaw, pitch, roll, landmarks[1, 0], landmarks[1, 1], 1 / interocular),
        dtype=np.float32,
    )
    face = np.asarray([blendshapes.get(name, 0.0) for name in BLENDSHAPE_NAMES], dtype=np.float32)
    result = np.concatenate((gaze, head, face)).astype(np.float32, copy=False)
    if result.shape != (RAW_FEATURE_COUNT,) or not np.isfinite(result).all():
        raise InvalidFrameFeaturesError("expected 49 finite MediaPipe features")
    return result


__all__ = [
    "BLENDSHAPE_NAMES",
    "RAW_FEATURE_COUNT",
    "SCHEMA_NAME",
    "TOKEN_FEATURE_COUNT",
    "InvalidFrameFeaturesError",
    "extract_frame_features",
]
