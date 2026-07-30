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
import socket
import subprocess
import time
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import NoReturn

#: Filenames the publisher moves. ``best.pt`` and ONNX exports are deliberately
#: absent: they are large or binary, and judging a run needs only these four.
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


def sync_metrics(metrics: Sequence[MetricFile], destination: Path) -> list[Path]:
    """Write the metrics whose bytes differ from what is already there.

    Returns the relative paths actually written, which is what decides whether
    there is anything to commit. Writing unchanged files would produce a commit
    every interval with no new information.
    """
    written: list[Path] = []
    for metric in metrics:
        target = destination / metric.relative
        if target.is_file() and target.read_bytes() == metric.data:
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(metric.data)
        written.append(metric.relative)
    return written


#: The orphan branch metrics live on. Not a code branch and never merged.
DEFAULT_RESULTS_BRANCH = "ai/results"

#: Kept out of the branch by the results branch's own ``.gitignore``.
LOCK_FILENAME = ".publish.lock"

#: Keeps a metrics snapshot from waking the Jenkins job.
SKIP_CI_MARKER = "[skip ci]"

#: Beyond this the subject stops being scannable, so the rest is summarised.
_MAX_LISTED_PROTOCOLS = 3


def _git(worktree: Path, *args: str) -> str:
    """Run git in ``worktree``, raising with git's own stderr on failure."""
    completed = subprocess.run(
        ("git", "-C", str(worktree), *args),
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )
    if completed.returncode != 0:
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"git {' '.join(args)} failed in {worktree}: {detail}")
    return completed.stdout


def _abort_rebase(worktree: Path) -> None:
    """Best-effort cleanup so a conflict does not wedge the next interval."""
    subprocess.run(
        ("git", "-C", str(worktree), "rebase", "--abort"),
        capture_output=True,
        text=True,
        check=False,
    )


def _ref_exists(worktree: Path, ref: str) -> bool:
    """Whether ``ref`` resolves, without treating its absence as a failure."""
    completed = subprocess.run(
        ("git", "-C", str(worktree), "rev-parse", "--verify", "--quiet", ref),
        capture_output=True,
        text=True,
        encoding="utf-8",
        check=False,
    )
    return completed.returncode == 0


def _unpushed_commits(worktree: Path, branch: str) -> int:
    """How many commits ``HEAD`` has that ``origin/<branch>`` does not.

    This is what makes a failed push recoverable. Whether a snapshot still needs
    sending cannot be read off the metric files alone: they were already copied
    into the worktree by the cycle whose push failed, so the next cycle sees
    nothing to write while its commit sits there unpushed.

    A missing ``origin/<branch>`` -- a clone that has never pushed or fetched the
    branch -- counts as unpushed. An absent ref proves nothing about what the
    remote holds, and the whole point is never to sit on work silently: the cycle
    then tries ``pull --rebase`` and either succeeds, which creates the ref, or
    fails loudly for the next cycle to retry. An unborn ``HEAD`` has nothing to
    send and counts as 0.
    """
    if not _ref_exists(worktree, "HEAD"):
        return 0
    remote_ref = f"refs/remotes/origin/{branch}"
    if not _ref_exists(worktree, remote_ref):
        return 1
    return int(_git(worktree, "rev-list", "--count", f"{remote_ref}..HEAD").strip() or "0")


def require_results_branch(worktree: Path, branch: str) -> None:
    """Fail before writing anything unless ``worktree`` has ``branch`` out.

    Aimed at the training clone by accident, the publisher would stage code
    changes and could change files under a run in progress.
    """
    current = _git(worktree, "rev-parse", "--abbrev-ref", "HEAD").strip()
    if current != branch:
        raise ValueError(f"worktree {worktree} is on {current!r}, expected {branch!r}")


def commit_subject(source_label: str, written: Sequence[Path]) -> str:
    """One scannable line naming where the snapshot came from and what moved.

    The first path segment is read as a protocol name, which holds only when
    ``--artifacts`` points at the directory the protocol outputs sit under, as in
    ``--artifacts artifacts/engagement``. Point it one level deeper and the
    segment is a seed directory instead; the subject is then less informative but
    still correct about the file count.
    """
    protocols = sorted({path.parts[0] for path in written if path.parts})
    if len(protocols) > _MAX_LISTED_PROTOCOLS:
        listed = protocols[:_MAX_LISTED_PROTOCOLS]
        detail = f"{', '.join(listed)} 외 {len(protocols) - _MAX_LISTED_PROTOCOLS}개"
    else:
        detail = ", ".join(protocols)
    return f"🔧 chore: {source_label} 지표 스냅샷 — {detail} {len(written)}개 파일 {SKIP_CI_MARKER}"


def publish_once(
    *,
    artifacts_root: Path,
    worktree: Path,
    source_label: str,
    branch: str,
) -> int:
    """Copy, commit and push one snapshot; return how many files were written.

    Returns 0 without touching git only when nothing changed **and** nothing is
    waiting to be pushed. Skipping the push whenever no file changed would strand
    the commit of a cycle whose push failed: its bytes are already in the
    worktree, so no later cycle would ever write a file again and the snapshot
    would sit on the box until the idle culler took it.

    Locking and the branch check belong to the caller, which holds them for the
    whole process.
    """
    written = sync_metrics(collect_metrics(artifacts_root), worktree / source_label)
    if written:
        _git(worktree, "add", "--all", "--", source_label)
        if _git(worktree, "status", "--porcelain", "--", source_label).strip():
            _git(worktree, "commit", "-m", commit_subject(source_label, written))
        else:
            # Bytes changed on disk but git sees nothing to record, so there is
            # no snapshot to report either.
            written = []
    if not written and not _unpushed_commits(worktree, branch):
        return 0
    try:
        _git(worktree, "pull", "--rebase", "origin", branch)
    except RuntimeError:
        _abort_rebase(worktree)
        raise
    _git(worktree, "push", "origin", branch)
    if not written:
        # Nothing was written, so the caller's own report stays silent while the
        # log still ends on the earlier failure line. Said here rather than from a
        # flag in run_forever: a flag would also fire for the git-identity case,
        # where the next cycle returns 0 having sent nothing.
        print("pushed a snapshot an earlier cycle could not send", flush=True)
    return len(written)


def default_source_label() -> str:
    """Short hostname, which is what distinguishes one training box from another."""
    return socket.gethostname().split(".")[0]


def run_forever(
    *,
    artifacts_root: Path,
    worktree: Path,
    source_label: str,
    branch: str,
    interval: float,
) -> NoReturn:
    """Publish every ``interval`` seconds, surviving anything one cycle throws.

    A push that fails on a flaky network must not end the loop: the next cycle
    sends the same snapshot. Misconfiguration is checked by the caller before
    the loop, so what reaches here is worth retrying.
    """
    while True:
        try:
            written = publish_once(
                artifacts_root=artifacts_root,
                worktree=worktree,
                source_label=source_label,
                branch=branch,
            )
        except (RuntimeError, OSError) as error:
            print(f"publish failed, retrying in {interval:.0f}s: {error}", flush=True)
        else:
            if written:
                print(f"published {written} file(s)", flush=True)
        time.sleep(interval)


__all__ = [
    "DEFAULT_RESULTS_BRANCH",
    "LOCK_FILENAME",
    "METRIC_FILENAMES",
    "SKIP_CI_MARKER",
    "MetricFile",
    "collect_metrics",
    "commit_subject",
    "default_source_label",
    "publish_once",
    "require_results_branch",
    "run_forever",
    "sync_metrics",
]
