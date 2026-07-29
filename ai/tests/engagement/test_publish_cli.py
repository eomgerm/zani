"""The publish-results subcommand.

``--interval 0`` is the single-shot mode the training wrapper uses at exit. A
second publisher must be rejected immediately rather than queue, matching how
parallel seeds treat ``.seed.lock``.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from zani_ai.engagement.cli import main
from zani_ai.engagement.locking import DirectoryLock
from zani_ai.engagement.publish import DEFAULT_RESULTS_BRANCH, LOCK_FILENAME


def _git(cwd: Path, *args: str) -> str:
    completed = subprocess.run(
        ("git", *args), cwd=cwd, check=True, capture_output=True, text=True, encoding="utf-8"
    )
    return completed.stdout


@pytest.fixture
def worktree(tmp_path: Path) -> Path:
    origin = tmp_path / "origin.git"
    origin.mkdir()
    _git(origin, "init", "--bare", "-b", DEFAULT_RESULTS_BRANCH)
    tree = tmp_path / "results"
    tree.mkdir()
    _git(tree, "init", "-b", DEFAULT_RESULTS_BRANCH)
    _git(tree, "config", "user.email", "test@example.com")
    _git(tree, "config", "user.name", "publisher test")
    _git(tree, "config", "commit.gpgsign", "false")
    _git(tree, "commit", "--allow-empty", "-m", "root")
    _git(tree, "remote", "add", "origin", str(origin))
    _git(tree, "push", "-u", "origin", DEFAULT_RESULTS_BRANCH)
    return tree


@pytest.fixture
def artifacts(tmp_path: Path) -> Path:
    root = tmp_path / "artifacts" / "engagement" / "e1"
    root.mkdir(parents=True)
    (root / "summary.json").write_bytes(b'{"protocol": "E1"}\n')
    return root.parent


def _argv(artifacts: Path, worktree: Path) -> list[str]:
    return [
        "publish-results",
        "--artifacts",
        str(artifacts),
        "--worktree",
        str(worktree),
        "--source",
        "l40s",
        "--interval",
        "0",
    ]


def test_single_shot_publishes_and_exits_zero(artifacts: Path, worktree: Path) -> None:
    assert main(_argv(artifacts, worktree)) == 0

    pushed = _git(worktree, "ls-tree", "-r", "--name-only", f"origin/{DEFAULT_RESULTS_BRANCH}")
    assert "l40s/e1/summary.json" in pushed


def test_second_publisher_is_refused(artifacts: Path, worktree: Path) -> None:
    held = DirectoryLock(worktree / LOCK_FILENAME, busy_message="already publishing")
    held.acquire()
    try:
        assert main(_argv(artifacts, worktree)) == 2
    finally:
        held.release()


def test_wrong_branch_exits_nonzero(artifacts: Path, worktree: Path) -> None:
    _git(worktree, "switch", "-c", "dev")

    assert main(_argv(artifacts, worktree)) == 2
