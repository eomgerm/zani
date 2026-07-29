"""One publish cycle against a real git repository.

A local bare repository stands in for GitLab, so push and ``pull --rebase`` are
exercised without a network. The publisher must refuse a worktree that is not on
the results branch: pointing it at the training clone by mistake would stage
code and could change files under a running training process.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from zani_ai.engagement.publish import (
    DEFAULT_RESULTS_BRANCH,
    commit_subject,
    publish_once,
    require_results_branch,
)


def _git(cwd: Path, *args: str) -> str:
    completed = subprocess.run(
        ("git", *args), cwd=cwd, check=True, capture_output=True, text=True, encoding="utf-8"
    )
    return completed.stdout


@pytest.fixture
def worktree(tmp_path: Path) -> Path:
    """A repository on the results branch whose origin is a local bare repo."""
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
    (root / "seed-42").mkdir(parents=True)
    (root / "summary.json").write_bytes(b'{"protocol": "E1"}\n')
    (root / "seed-42" / "record.json").write_bytes(b'{"seed": 42}\n')
    (root / "seed-42" / "best.pt").write_bytes(b"binary")
    return root.parent


def test_commits_and_pushes_the_metrics(worktree: Path, artifacts: Path) -> None:
    written = publish_once(
        artifacts_root=artifacts,
        worktree=worktree,
        source_label="l40s",
        branch=DEFAULT_RESULTS_BRANCH,
    )

    assert written == 2
    pushed = _git(worktree, "ls-tree", "-r", "--name-only", f"origin/{DEFAULT_RESULTS_BRANCH}")
    assert "l40s/e1/summary.json" in pushed
    assert "l40s/e1/seed-42/record.json" in pushed
    assert "best.pt" not in pushed


def test_makes_no_commit_when_nothing_changed(worktree: Path, artifacts: Path) -> None:
    publish_once(
        artifacts_root=artifacts,
        worktree=worktree,
        source_label="l40s",
        branch=DEFAULT_RESULTS_BRANCH,
    )
    before = _git(worktree, "rev-parse", "HEAD").strip()

    written = publish_once(
        artifacts_root=artifacts,
        worktree=worktree,
        source_label="l40s",
        branch=DEFAULT_RESULTS_BRANCH,
    )

    assert written == 0
    assert _git(worktree, "rev-parse", "HEAD").strip() == before


def test_pushes_a_stranded_commit_on_the_next_cycle(
    worktree: Path, artifacts: Path, tmp_path: Path
) -> None:
    """A cycle whose push failed must send the same commit on the next one.

    ``sync_metrics`` compares candidates against the destination worktree, which
    already holds the bytes the failed cycle wrote, so nothing is written the
    second time round. Deciding purely on written files would leave the commit
    on the server until the idle culler took the box, losing every metric since
    the failure rather than one interval's worth.
    """
    origin = tmp_path / "origin.git"
    moved = tmp_path / "origin.git.gone"
    origin.rename(moved)

    with pytest.raises(RuntimeError):
        publish_once(
            artifacts_root=artifacts,
            worktree=worktree,
            source_label="l40s",
            branch=DEFAULT_RESULTS_BRANCH,
        )
    stranded = _git(worktree, "rev-list", "--count", f"origin/{DEFAULT_RESULTS_BRANCH}..HEAD")
    assert stranded.strip() == "1"

    moved.rename(origin)
    written = publish_once(
        artifacts_root=artifacts,
        worktree=worktree,
        source_label="l40s",
        branch=DEFAULT_RESULTS_BRANCH,
    )

    assert written == 0
    pushed = _git(worktree, "ls-tree", "-r", "--name-only", f"origin/{DEFAULT_RESULTS_BRANCH}")
    assert "l40s/e1/summary.json" in pushed
    remaining = _git(worktree, "rev-list", "--count", f"origin/{DEFAULT_RESULTS_BRANCH}..HEAD")
    assert remaining.strip() == "0"


def test_publishes_when_the_branch_was_never_pushed(worktree: Path, artifacts: Path) -> None:
    """A missing ``origin/<branch>`` ref must not be read as nothing to do."""
    publish_once(
        artifacts_root=artifacts,
        worktree=worktree,
        source_label="l40s",
        branch=DEFAULT_RESULTS_BRANCH,
    )
    _git(worktree, "update-ref", "-d", f"refs/remotes/origin/{DEFAULT_RESULTS_BRANCH}")

    written = publish_once(
        artifacts_root=artifacts,
        worktree=worktree,
        source_label="l40s",
        branch=DEFAULT_RESULTS_BRANCH,
    )

    assert written == 0
    pushed = _git(worktree, "ls-tree", "-r", "--name-only", f"origin/{DEFAULT_RESULTS_BRANCH}")
    assert "l40s/e1/summary.json" in pushed


def test_refuses_a_worktree_on_another_branch(worktree: Path) -> None:
    _git(worktree, "switch", "-c", "dev")

    with pytest.raises(ValueError, match="expected"):
        require_results_branch(worktree, DEFAULT_RESULTS_BRANCH)


def test_commit_subject_names_the_source_and_skips_ci() -> None:
    subject = commit_subject("l40s", [Path("e1/summary.json"), Path("e1/seed-42/record.json")])

    assert subject.startswith("🔧 chore: l40s 지표 스냅샷")
    assert "e1" in subject
    assert subject.endswith("[skip ci]")
    assert len(subject) <= 100
