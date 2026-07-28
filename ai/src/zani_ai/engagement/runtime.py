"""Execution-environment resolution shared by the experiment and report paths.

Two things vary with *where* a run happens rather than *what* is being run: the
physical device it lands on, and where the landmark graph file lives. A laptop
with one GPU, a shared server where only card 2 is allocated, a Jupyter kernel
whose working directory is not the project root -- all run the same protocol.

Keeping both concerns here makes that separation explicit: nothing in this
module may reach ``experiment._build_configuration``, because anything that
does becomes part of the reproducibility identity and invalidates every
completed seed.
"""

from __future__ import annotations

import os
import re
from pathlib import Path

#: Filename ``build-features`` gives the ST-GCN landmark graph.
GRAPH_FILENAME = "landmark_78_v1_graph.npz"

#: Environment variable holding an explicit landmark graph path.
GRAPH_PATH_ENV = "ZANI_LANDMARK_GRAPH"

_CUDA_INDEX_PATTERN = re.compile(r"^cuda:(\d+)$")


def parse_device(device: str) -> tuple[str, int | None]:
    """Split ``device`` into its normalized type and optional CUDA index.

    Accepts ``cpu``, ``cuda`` and ``cuda:N``. ``cpu:N`` is rejected -- an index
    on the CPU is meaningless and almost always a typo for ``cuda:N``.
    """
    text = device.strip()
    if text in {"cpu", "cuda"}:
        return text, None
    match = _CUDA_INDEX_PATTERN.fullmatch(text)
    if match is None:
        raise ValueError(f"device must be 'cpu', 'cuda' or 'cuda:N', got {device!r}")
    return "cuda", int(match.group(1))


def device_type(device: str) -> str:
    """The protocol-visible device, with any card index stripped.

    ``cuda`` and ``cuda:2`` are the same experiment on different hardware, so
    only this value may appear in ``configuration``; the index belongs in
    ``environment.cuda_device.index``.
    """
    return parse_device(device)[0]


def resolve_landmark_graph(features_root: Path, explicit: Path | None = None) -> Path:
    """Locate the ST-GCN landmark graph for a run rooted at ``features_root``.

    An explicit path (CLI ``--graph``) or :data:`GRAPH_PATH_ENV` is used as
    given and must exist -- silently falling back after someone stated an
    intent hides stale configuration. With neither, the graph is searched for
    next to the features and then one level up, which is where
    ``build-features`` leaves it in the shared ``processed/<dataset>/``
    directory.

    Raises:
        FileNotFoundError: if no candidate exists, listing every path tried.
    """
    if explicit is not None:
        stated = Path(explicit)
        if not stated.is_file():
            raise FileNotFoundError(f"--graph does not point at a file: {stated}")
        return stated

    from_environment = os.environ.get(GRAPH_PATH_ENV)
    if from_environment:
        stated = Path(from_environment)
        if not stated.is_file():
            raise FileNotFoundError(
                f"{GRAPH_PATH_ENV} does not point at a file: {stated}"
            )
        return stated

    candidates = (
        features_root / GRAPH_FILENAME,
        features_root.parent / GRAPH_FILENAME,
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    tried = "\n  ".join(str(candidate) for candidate in candidates)
    raise FileNotFoundError(
        f"landmark graph not found; pass --graph or set {GRAPH_PATH_ENV}.\n"
        f"Tried:\n  {tried}"
    )


__all__ = [
    "GRAPH_FILENAME",
    "GRAPH_PATH_ENV",
    "device_type",
    "parse_device",
    "resolve_landmark_graph",
]
