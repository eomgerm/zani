"""Seeds of one protocol must be runnable as concurrent processes.

The driver used to write ``summary.json`` after every seed, so two processes
sharing an output directory would clobber each other's records. Completion now
lives in ``seed-<n>/record.json`` and the summary is rebuilt from those, which
is what these tests pin down -- including that a run made under the old layout
still resumes instead of retraining.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import pytest

# Reuses the E1 wiring module's tiny synthetic dataset rather than duplicating
# it. `features` is re-exported so pytest resolves it as a fixture here too.
from test_experiment_e1_wiring import TINY_E1, _write_graph, features

from zani_ai.engagement.experiment import (
    ExperimentSpec,
    collect_only,
    prepare_run,
    reproduce_experiment,
    run_seed,
)
from zani_ai.engagement.locking import DirectoryLock, LockUnavailableError
from zani_ai.engagement.runtime import GRAPH_FILENAME

__all__ = ["features"]

#: Two seeds, so "one process per seed" has something to divide.
TINY_TWO_SEED: ExperimentSpec = replace(TINY_E1, seeds=(42, 43))


def _record_path(output: Path, seed: int) -> Path:
    return output / f"seed-{seed}" / "record.json"


def _run_one(features_root: Path, output: Path, seed: int) -> None:
    """What a single parallel worker does: one seed, no summary write."""
    reproduce_experiment(
        TINY_TWO_SEED,
        features_root,
        output,
        device="cpu",
        seeds=(seed,),
        collect=False,
    )


def test_a_single_seed_worker_leaves_the_summary_alone(features: Path, tmp_path: Path) -> None:
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"

    _run_one(features, output, 42)

    assert _record_path(output, 42).is_file()
    assert not _record_path(output, 43).exists()
    assert not (output / "summary.json").exists()


def test_collect_only_rebuilds_the_summary_from_records(features: Path, tmp_path: Path) -> None:
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"
    _run_one(features, output, 42)
    _run_one(features, output, 43)

    result = collect_only(TINY_TWO_SEED, features, output, device="cpu")

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    assert result.completed_seeds == (42, 43)
    assert summary["status"] == "complete"
    assert [record["seed"] for record in summary["seeds"]] == [42, 43]
    assert summary["aggregate"]["completed_seed_count"] == 2


def test_a_partial_collect_reports_in_progress(features: Path, tmp_path: Path) -> None:
    """Collecting while other seeds still run must not claim completion."""
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"
    _run_one(features, output, 42)

    result = collect_only(TINY_TWO_SEED, features, output, device="cpu")

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    assert result.completed_seeds == (42,)
    assert summary["status"] == "in_progress"


def test_parallel_seeds_match_a_sequential_run(features: Path, tmp_path: Path) -> None:
    """Splitting seeds across processes must not change the recorded results."""
    _write_graph(features / GRAPH_FILENAME)
    sequential = reproduce_experiment(
        TINY_TWO_SEED, features, tmp_path / "sequential", device="cpu"
    )
    parallel_output = tmp_path / "parallel"
    _run_one(features, parallel_output, 42)
    _run_one(features, parallel_output, 43)
    parallel = collect_only(TINY_TWO_SEED, features, parallel_output, device="cpu")

    def validations(path: Path) -> list[object]:
        summary = json.loads(path.read_text(encoding="utf-8"))
        return [(r["seed"], r["best_epoch"], r["validation"]) for r in summary["seeds"]]

    assert sequential.completed_seeds == parallel.completed_seeds
    assert validations(sequential.summary_path) == validations(parallel.summary_path)


def test_a_second_process_cannot_enter_a_running_seed(features: Path, tmp_path: Path) -> None:
    """The lock is what stops two workers from training the same seed."""
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"
    context = prepare_run(TINY_TWO_SEED, features, output, device="cpu")
    held = DirectoryLock(output / "seed-42" / ".seed.lock", busy_message="held")
    held.acquire()

    try:
        with pytest.raises(LockUnavailableError, match="already running"):
            run_seed(context, 42)
    finally:
        held.release()


def test_run_seed_rejects_a_seed_outside_the_protocol(features: Path, tmp_path: Path) -> None:
    _write_graph(features / GRAPH_FILENAME)
    context = prepare_run(TINY_TWO_SEED, features, tmp_path / "run", device="cpu")

    with pytest.raises(ValueError, match="has no seed 99"):
        run_seed(context, 99)


def test_a_run_from_before_per_seed_records_still_resumes(features: Path, tmp_path: Path) -> None:
    """Completion used to live only in summary.json; those runs must not retrain."""
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"
    reproduce_experiment(TINY_TWO_SEED, features, output, device="cpu")
    checkpoint = output / "seed-42" / "best.pt"
    stamp = checkpoint.stat().st_mtime_ns
    for seed in TINY_TWO_SEED.seeds:
        _record_path(output, seed).unlink()

    context = prepare_run(TINY_TWO_SEED, features, output, device="cpu")

    assert _record_path(output, 42).is_file()
    assert run_seed(context, 42) is False
    assert checkpoint.stat().st_mtime_ns == stamp
