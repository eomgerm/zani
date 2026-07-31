from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray


@dataclass(frozen=True, slots=True)
class FeatureSchema:
    """Feature vector schema with blendshape names and dimensions."""

    name: str
    blendshape_names: tuple[str, ...]
    gaze_dim: int = 8
    head_dim: int = 6

    @property
    def raw_feature_count(self) -> int:
        return self.gaze_dim + self.head_dim + len(self.blendshape_names)

    @property
    def token_feature_count(self) -> int:
        return 2 * self.raw_feature_count


BLENDSHAPE_NAMES_98 = (
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

BLENDSHAPE_NAMES_132 = (
    "_neutral",
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
    "jawForward",
    "jawLeft",
    "jawOpen",
    "jawRight",
    "mouthClose",
    "mouthDimpleLeft",
    "mouthDimpleRight",
    "mouthFrownLeft",
    "mouthFrownRight",
    "mouthFunnel",
    "mouthLeft",
    "mouthLowerDownLeft",
    "mouthLowerDownRight",
    "mouthPressLeft",
    "mouthPressRight",
    "mouthPucker",
    "mouthRight",
    "mouthRollLower",
    "mouthRollUpper",
    "mouthShrugLower",
    "mouthShrugUpper",
    "mouthSmileLeft",
    "mouthSmileRight",
    "mouthStretchLeft",
    "mouthStretchRight",
    "mouthUpperUpLeft",
    "mouthUpperUpRight",
    "noseSneerLeft",
    "noseSneerRight",
)

SCHEMA_98 = FeatureSchema("mediapipe_98_v1", BLENDSHAPE_NAMES_98)
SCHEMA_98_PLACEHOLDER = FeatureSchema(
    "mediapipe_98_placeholder_v1",
    BLENDSHAPE_NAMES_98,
)
SCHEMA_132 = FeatureSchema("mediapipe_132_v1", BLENDSHAPE_NAMES_132)
SCHEMAS = {s.name: s for s in (SCHEMA_98, SCHEMA_98_PLACEHOLDER, SCHEMA_132)}


def get_schema(name: str) -> FeatureSchema:
    """Get a feature schema by name.

    Args:
        name: The schema name (e.g., "mediapipe_98_v1", "mediapipe_132_v1")

    Returns:
        The FeatureSchema with the given name.

    Raises:
        ValueError: If the schema name is not found.
    """
    try:
        return SCHEMAS[name]
    except KeyError:
        raise ValueError(f"unknown feature schema: {name}") from None


# Back-compatibility aliases for existing code
SCHEMA_NAME = SCHEMA_98.name
BLENDSHAPE_NAMES = BLENDSHAPE_NAMES_98
RAW_FEATURE_COUNT = SCHEMA_98.raw_feature_count
TOKEN_FEATURE_COUNT = SCHEMA_98.token_feature_count

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
    *,
    schema: FeatureSchema = SCHEMA_98,
) -> NDArray[np.float32]:
    """Convert one Face Landmarker result to the raw feature contract.

    Args:
        landmarks: Face landmarks array of shape (478, 3) with xyz coordinates.
        transform: 4x4 face transform matrix.
        blendshapes: Mapping of blendshape names to float values.
        schema: Feature schema to use (default: SCHEMA_98 for 49-dim features).

    Returns:
        Feature vector of shape (schema.raw_feature_count,) with dtype float32.

    Raises:
        InvalidFrameFeaturesError: If inputs are invalid or outputs are non-finite.
    """
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
    face = np.asarray(
        [blendshapes.get(name, 0.0) for name in schema.blendshape_names],
        dtype=np.float32,
    )
    result = np.concatenate((gaze, head, face)).astype(np.float32, copy=False)
    if result.shape != (schema.raw_feature_count,) or not np.isfinite(result).all():
        raise InvalidFrameFeaturesError(
            f"expected {schema.raw_feature_count} finite {schema.name} features"
        )
    return result


__all__ = [
    "BLENDSHAPE_NAMES",
    "BLENDSHAPE_NAMES_98",
    "BLENDSHAPE_NAMES_132",
    "RAW_FEATURE_COUNT",
    "SCHEMAS",
    "SCHEMA_98",
    "SCHEMA_98_PLACEHOLDER",
    "SCHEMA_132",
    "SCHEMA_NAME",
    "TOKEN_FEATURE_COUNT",
    "FeatureSchema",
    "InvalidFrameFeaturesError",
    "extract_frame_features",
    "get_schema",
]
