"""Fixed 78-landmark index list and ST-GCN spatial-configuration graph.

This module defines the E1 (ST-GCN) input topology: which 78 of MediaPipe
FaceMesh's 478 landmarks are used as graph nodes, and the fixed spatial graph
(Delaunay-triangulated adjacency partitioned into 3 subsets per the
spatial-configuration partitioning strategy of Yan, Xiong & Lin, "Spatial
Temporal Graph Convolutional Networks for Skeleton-Based Action Recognition",
AAAI 2018).

Node ordering (fixed, defines node indices 0..77 for the entire E1 pipeline):
    68 face landmarks (Dlib-68-like layout) followed by 10 iris landmarks.

IMPORTANT — approximation notice
---------------------------------
MediaPipe FaceMesh does not use the classic Dlib-68 landmark scheme, and there
is no official, canonical index correspondence between the two. The 68 face
indices below are a documented, hand-picked APPROXIMATION: for each Dlib-68
region (jaw / eyebrows / nose / eyes / mouth) we select MediaPipe indices from
the well-known, widely published MediaPipe FaceMesh landmark groups (the
canonical ``FACEMESH_FACE_OVAL``, ``FACEMESH_LEFT_EYEBROW``,
``FACEMESH_RIGHT_EYEBROW``, ``FACEMESH_LEFT_EYE``, ``FACEMESH_RIGHT_EYE`` and
``FACEMESH_LIPS`` connection sets, plus the commonly documented single-point
nose landmarks: tip=4, bridge=168/197/195/5, alae=98/129/327/358) and subsample
them down to Dlib-68's per-region point counts (jaw 17, eyebrows 5+5, nose 9,
eyes 6+6, mouth 12+8). This mirrors the existing "mediapipe_98_v1" /
"mediapipe_132_v1" blendshape-derived gaze/AU proxies used elsewhere in this
package (see ``features.py``): both are *documented approximations*, not
ground-truth re-implementations of the original (Dlib / OpenFace AU) schemes.
The exact node identities do not need to match Dlib pixel-for-pixel — what
matters for ST-GCN is a stable, anatomically plausible, fixed set of 78 nodes
with a fixed adjacency, which this module provides.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
from numpy.typing import NDArray
from scipy.spatial import Delaunay

GRAPH_VERSION = "landmark_78_v1"

# ---------------------------------------------------------------------------
# Step 1: 78 landmark indices
# ---------------------------------------------------------------------------
# 68 "face" landmarks approximating the classic Dlib-68 layout, selected from
# MediaPipe FaceMesh's canonical (0..467) landmark set. Order follows the
# Dlib-68 convention: jaw (17) -> eyebrows (10) -> nose (9) -> eyes (12) ->
# mouth (20).

# Jaw / face contour (17 points), sampled from the canonical MediaPipe
# ``FACEMESH_FACE_OVAL`` loop, restricted to its lower/side arc (excluding the
# forehead points) and going from one ear, through the chin, to the other ear
# -- analogous to Dlib points 0..16.
FACE_JAW_17 = (
    454, 323, 361, 397, 365, 379, 400, 377,  # right ear -> chin (right side)
    152,  # chin (Dlib point 8)
    148, 176, 150, 136, 58, 132, 234, 127,  # chin -> left ear (left side)
)

# Eyebrows (10 points = 5 + 5), sampled outer-to-inner from the canonical
# ``FACEMESH_RIGHT_EYEBROW`` / ``FACEMESH_LEFT_EYEBROW`` groups -- analogous
# to Dlib points 17..26.
FACE_EYEBROWS_10 = (
    70, 63, 105, 66, 107,  # eyebrow, outer -> inner
    336, 296, 334, 293, 300,  # other eyebrow, inner -> outer
)

# Nose (9 points): bridge (glabella -> just above tip, 4 points) followed by
# tip and alae/nostril-wing points (5 points, left-to-right) -- analogous to
# Dlib points 27..35.
FACE_NOSE_9 = (
    168, 197, 195, 5,  # bridge, top (between eyebrows) -> bottom (near tip)
    129, 98, 4, 327, 358,  # left ala, left-of-tip, tip, right-of-tip, right ala
)

# Eyes (12 points = 6 + 6), each a 6-point hexagon (outer corner, 2 upper-lid
# points, inner corner, 2 lower-lid points) sampled from the canonical
# ``FACEMESH_LEFT_EYE`` / ``FACEMESH_RIGHT_EYE`` groups -- analogous to Dlib
# points 36..47.
FACE_EYES_12 = (
    33, 160, 158, 133, 153, 144,  # eye 1: outer, upper x2, inner, lower x2
    263, 387, 385, 362, 380, 374,  # eye 2: outer, upper x2, inner, lower x2
)

# Mouth (20 points = 12 outer + 8 inner), subsampled from the canonical
# ``FACEMESH_LIPS`` outer and inner rings -- analogous to Dlib points 48..67.
FACE_MOUTH_20 = (
    61, 40, 37, 0, 269, 409, 291, 321, 314, 17, 181, 146,  # outer ring (12)
    78, 80, 13, 415, 308, 318, 14, 178,  # inner ring (8)
)

FACE_68: tuple[int, ...] = (
    FACE_JAW_17 + FACE_EYEBROWS_10 + FACE_NOSE_9 + FACE_EYES_12 + FACE_MOUTH_20
)

# 10 iris landmarks: MediaPipe's refined-landmarks output appends 5 right-iris
# points (468..472) followed by 5 left-iris points (473..477).
IRIS_10: tuple[int, ...] = tuple(range(468, 478))

NODE_COUNT = 78

#: Fixed 78-node ordering for the whole E1 pipeline: face 68 (Dlib-68 order)
#: followed by iris 10. Do not reorder -- this defines node indices 0..77.
LANDMARK_78_INDICES: tuple[int, ...] = FACE_68 + IRIS_10

assert len(FACE_68) == 68, f"expected 68 face indices, got {len(FACE_68)}"
assert len(LANDMARK_78_INDICES) == NODE_COUNT
assert len(set(LANDMARK_78_INDICES)) == NODE_COUNT, "duplicate landmark index"
assert all(0 <= i < 478 for i in LANDMARK_78_INDICES), "landmark index out of range"


# ---------------------------------------------------------------------------
# Step 2: spatial-configuration partitioning (Yan et al., 2018)
# ---------------------------------------------------------------------------
def build_spatial_partitions(mean_xy: NDArray[np.floating]) -> NDArray[np.float32]:
    """Build the 3-subset ST-GCN spatial-configuration adjacency.

    Given the mean (x, y) position of each of the 78 nodes (e.g. averaged
    over a dataset or a clip), triangulate the node layout with Delaunay to
    get a fixed undirected spatial adjacency, then partition each edge into
    one of the three spatial-configuration subsets (Yan, Xiong & Lin 2018):

    - root: self-loops (identity) -- a node's own feature.
    - centripetal: edges pointing from a farther node to a nearer node
      (relative to the skeleton/graph centroid).
    - centrifugal: edges pointing from a nearer node to a farther node.

    Each subset's adjacency is symmetric-normalized as ``D^{-1/2} M D^{-1/2}``
    (with zero-degree nodes guarded against division by zero) before being
    stacked into the final [3, 78, 78] tensor consumed by ST-GCN.

    Args:
        mean_xy: Array of shape [78, 2] with the mean 2D position of each
            node.

    Returns:
        Array of shape [3, 78, 78], dtype float32, order
        (root, centripetal, centrifugal). Always finite.
    """
    mean_xy = np.asarray(mean_xy, dtype=np.float64)
    if mean_xy.shape != (NODE_COUNT, 2):
        raise ValueError(f"expected mean_xy shape ({NODE_COUNT}, 2), got {mean_xy.shape}")

    tri = Delaunay(mean_xy)
    adjacency = np.zeros((NODE_COUNT, NODE_COUNT), dtype=np.float64)
    for simplex in tri.simplices:
        for a in simplex:
            for b in simplex:
                if a != b:
                    adjacency[a, b] = 1.0
                    adjacency[b, a] = 1.0

    center = mean_xy.mean(axis=0)
    dist = np.linalg.norm(mean_xy - center, axis=1)

    root = np.eye(NODE_COUNT, dtype=np.float64)
    centripetal = np.zeros_like(adjacency)
    centrifugal = np.zeros_like(adjacency)
    for i in range(NODE_COUNT):
        for j in range(NODE_COUNT):
            if adjacency[i, j]:
                if dist[j] < dist[i]:
                    centripetal[i, j] = 1.0
                else:
                    centrifugal[i, j] = 1.0

    def _normalize(matrix: NDArray[np.float64]) -> NDArray[np.float64]:
        degree = matrix.sum(axis=1)
        degree_inv_sqrt = np.power(np.maximum(degree, 1e-6), -0.5)
        return (degree_inv_sqrt[:, None] * matrix) * degree_inv_sqrt[None, :]

    partitions = np.stack(
        [_normalize(root), _normalize(centripetal), _normalize(centrifugal)]
    )
    return partitions.astype(np.float32)


# ---------------------------------------------------------------------------
# Step 3: persistence
# ---------------------------------------------------------------------------
def save_graph(
    path: str | Path,
    indices: tuple[int, ...] | NDArray[np.integer],
    partitions: NDArray[np.float32],
) -> None:
    """Save the landmark indices and spatial partitions to ``path``.

    Writes an ``.npz`` archive (keys ``indices``, ``partitions``) alongside a
    small JSON sidecar (``<path>.json``) recording ``GRAPH_VERSION`` and
    shape metadata, for quick inspection without loading numpy arrays.
    """
    path = Path(path)
    if path.suffix != ".npz":
        path = path.with_suffix(path.suffix + ".npz")
    indices_arr = np.asarray(indices, dtype=np.int64)
    partitions_arr = np.asarray(partitions, dtype=np.float32)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as fh:
        np.savez(fh, indices=indices_arr, partitions=partitions_arr)

    sidecar = Path(str(path) + ".json")
    metadata = {
        "graph_version": GRAPH_VERSION,
        "node_count": int(indices_arr.shape[0]),
        "indices_shape": list(indices_arr.shape),
        "partitions_shape": list(partitions_arr.shape),
    }
    sidecar.write_text(json.dumps(metadata, indent=2, sort_keys=True), encoding="utf-8")


def load_graph(path: str | Path) -> tuple[NDArray[np.int64], NDArray[np.float32]]:
    """Load ``(indices, partitions)`` previously written by :func:`save_graph`."""
    path = Path(path)
    if path.suffix != ".npz":
        path = path.with_suffix(path.suffix + ".npz")
    with np.load(path) as data:
        indices = data["indices"].astype(np.int64)
        partitions = data["partitions"].astype(np.float32)
    return indices, partitions
