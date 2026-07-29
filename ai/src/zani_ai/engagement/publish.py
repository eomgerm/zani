"""Publish training metrics to the orphan results branch.

Training runs on a remote JupyterHub server whose idle culler stops the
singleuser systemd unit after 24 hours. ``KillMode=control-group`` then kills
every process in the cgroup and ``setsid`` does not escape a cgroup, so a run's
metrics have to leave the box while the run is still alive. This module copies
the small JSON metrics -- never checkpoints -- into a worktree of the results
branch and pushes them, so a reader elsewhere sees each seed land within one
interval.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

#: Filenames the publisher moves. ``best.pt``, ONNX exports and the HTML report
#: are deliberately absent: they are large or binary, and judging a run needs
#: only these four.
METRIC_FILENAMES = frozenset({"summary.json", "test_results.json", "record.json", "metrics.json"})


@dataclass(frozen=True, slots=True)
class MetricFile:
    """A metric file that parsed as JSON, carrying the bytes that parsed.

    Holding the bytes rather than the path means what gets committed is exactly
    what was validated; re-reading could pick up a half-written file.
    """

    relative: Path
    data: bytes


def collect_metrics(artifacts_root: Path) -> list[MetricFile]:
    """Read every valid metric JSON under ``artifacts_root``, sorted by path.

    A file that fails to parse is skipped rather than raising: ``metrics.json``
    is written without an atomic rename, so catching it mid-write is expected
    and the next interval will pick it up whole.
    """
    collected: list[MetricFile] = []
    for path in sorted(artifacts_root.rglob("*.json")):
        if path.name not in METRIC_FILENAMES:
            continue
        try:
            data = path.read_bytes()
            json.loads(data)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            continue
        collected.append(MetricFile(path.relative_to(artifacts_root), data))
    return collected


__all__ = ["METRIC_FILENAMES", "MetricFile", "collect_metrics"]
