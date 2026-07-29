"""The publish-results subcommand.

``--interval 0`` is the single-shot mode the training wrapper uses at exit. A
second *loop* must be rejected immediately rather than queue, matching how
parallel seeds treat ``.seed.lock``; a single shot that meets the periodic
publisher's lock has nothing to complain about, because that publisher will send
the same snapshot within one interval.

Misconfiguration must be loud. A wrong ``--artifacts`` or ``--source`` is a typo
nobody will notice from a loop that quietly publishes nothing forever.
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


def _argv(artifacts: Path, worktree: Path, interval: str = "0") -> list[str]:
    return [
        "publish-results",
        "--artifacts",
        str(artifacts),
        "--worktree",
        str(worktree),
        "--source",
        "l40s",
        "--interval",
        interval,
    ]


def test_single_shot_publishes_and_exits_zero(artifacts: Path, worktree: Path) -> None:
    assert main(_argv(artifacts, worktree)) == 0

    pushed = _git(worktree, "ls-tree", "-r", "--name-only", f"origin/{DEFAULT_RESULTS_BRANCH}")
    assert "l40s/e1/summary.json" in pushed


def test_a_second_loop_is_refused(artifacts: Path, worktree: Path) -> None:
    held = DirectoryLock(worktree / LOCK_FILENAME, busy_message="already publishing")
    held.acquire()
    try:
        assert main(_argv(artifacts, worktree, interval="300")) == 2
    finally:
        held.release()


def test_single_shot_defers_to_the_periodic_publisher(
    artifacts: Path, worktree: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """The documented setup runs both, so the exit publish must not always fail.

    Reporting failure when the periodic publisher holds the lock trains the
    operator to ignore ``publish failed`` in ``run_seeds_parallel.sh``'s output,
    which will one day mean something.
    """
    held = DirectoryLock(worktree / LOCK_FILENAME, busy_message="already publishing")
    held.acquire()
    try:
        assert main(_argv(artifacts, worktree)) == 0
    finally:
        held.release()

    assert "within one interval" in capsys.readouterr().out


def test_a_missing_artifacts_directory_exits_nonzero(worktree: Path, tmp_path: Path) -> None:
    """A typo'd --artifacts otherwise loops forever collecting nothing."""
    assert main(_argv(tmp_path / "typo", worktree)) == 2

    assert not (worktree / LOCK_FILENAME).exists()


@pytest.mark.parametrize("source", ["a/b", "a\\b", "..", "."])
def test_a_source_that_is_not_one_segment_is_rejected(
    artifacts: Path, worktree: Path, source: str
) -> None:
    """A separator nests the snapshot silently; ``..`` escapes the worktree."""
    argv = _argv(artifacts, worktree)
    argv[argv.index("l40s")] = source

    with pytest.raises(SystemExit):
        main(argv)


def test_wrong_branch_exits_nonzero(artifacts: Path, worktree: Path) -> None:
    _git(worktree, "switch", "-c", "dev")

    assert main(_argv(artifacts, worktree)) == 2
