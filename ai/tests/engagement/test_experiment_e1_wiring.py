"""End-to-end wiring for E1: graph resolution, binding, and provenance.

The unit tests cover ``resolve_landmark_graph`` and ``stgcn_model_builder``
separately. This module runs the real driver over a tiny synthetic dataset so
a break in how ``reproduce_experiment`` joins them -- resolving the graph,
rebinding ``build_model``, recording the fingerprint -- fails here rather than
on the training server.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import numpy as np
import pytest

from zani_ai.engagement.experiment import E1_SPEC, ExperimentSpec, reproduce_experiment
from zani_ai.engagement.landmark_graph import (
    LANDMARK_78_INDICES,
    NODE_COUNT,
    build_spatial_partitions,
    save_graph,
)
from zani_ai.engagement.runtime import GRAPH_FILENAME, GRAPH_PATH_ENV
from zani_ai.engagement.stgcn import STGCNConfig

SEQUENCE_SHAPE = (3, 100, NODE_COUNT)


def _write_graph(path: Path) -> Path:
    """A real graph file: Delaunay needs a non-degenerate node layout."""
    rng = np.random.default_rng(0)
    mean_xy = rng.uniform(0.0, 1.0, size=(NODE_COUNT, 2))
    save_graph(path, LANDMARK_78_INDICES, build_spatial_partitions(mean_xy))
    return path


def _write_features(root: Path) -> None:
    included: list[dict[str, object]] = []
    for split in ("train", "valid", "test"):
        for label in range(4):
            directory = root / "landmark_78_v1" / split
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"{split}-{label}.npz"
            rng = np.random.default_rng(label)
            sequence = rng.normal(label, 0.05, size=SEQUENCE_SHAPE).astype(np.float32)
            np.savez_compressed(path, sequence=sequence)
            included.append(
                {
                    "clip_id": f"{split}-{label}",
                    "split": split,
                    "label_index": label,
                    "feature_path": path.relative_to(root).as_posix(),
                    "source_fingerprint": f"{split}-{label}",
                }
            )
    (root / "manifest.json").write_text(
        json.dumps(
            {
                "schema": "landmark_78_v1",
                "status": "complete",
                "complete": True,
                "processed_count": len(included),
                "total_count": len(included),
                "included": included,
                "excluded": [],
            }
        ),
        encoding="utf-8",
    )


#: One seed, one epoch, a small network -- this exercises wiring, not learning.
TINY_E1: ExperimentSpec = replace(
    E1_SPEC,
    seeds=(42,),
    max_epochs=1,
    batch_size=4,
    model_config=STGCNConfig(channels=(8, 8)),
)


@pytest.fixture
def features(tmp_path: Path) -> Path:
    root = tmp_path / "processed" / "e1"
    root.mkdir(parents=True)
    _write_features(root)
    return root


def test_graph_beside_the_features_is_found_and_fingerprinted(
    features: Path, tmp_path: Path
) -> None:
    graph = _write_graph(features / GRAPH_FILENAME)

    result = reproduce_experiment(
        TINY_E1, features, tmp_path / "run", device="cpu"
    )

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    assert result.completed_seeds == (42,)
    assert summary["status"] == "complete"
    recorded = summary["inputs"]["landmark_graph"]
    assert recorded["path"] == str(graph.resolve())
    assert recorded["size_bytes"] == graph.stat().st_size


def test_graph_above_the_features_is_found(features: Path, tmp_path: Path) -> None:
    """The layout build-features actually produces: graph in processed/<dataset>/."""
    graph = _write_graph(features.parent / GRAPH_FILENAME)

    result = reproduce_experiment(TINY_E1, features, tmp_path / "run", device="cpu")

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    assert summary["inputs"]["landmark_graph"]["path"] == str(graph.resolve())


def test_explicit_graph_is_used(features: Path, tmp_path: Path) -> None:
    _write_graph(features / GRAPH_FILENAME)
    graph = _write_graph(tmp_path / "elsewhere" / "chosen.npz")

    result = reproduce_experiment(
        TINY_E1, features, tmp_path / "run", device="cpu", graph_path=graph
    )

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    assert summary["inputs"]["landmark_graph"]["path"] == str(graph.resolve())


def test_environment_variable_graph_is_used(
    features: Path, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    graph = _write_graph(tmp_path / "from-env.npz")
    monkeypatch.setenv(GRAPH_PATH_ENV, str(graph))

    result = reproduce_experiment(TINY_E1, features, tmp_path / "run", device="cpu")

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    assert summary["inputs"]["landmark_graph"]["path"] == str(graph.resolve())


def test_missing_graph_reports_the_paths_tried(features: Path, tmp_path: Path) -> None:
    with pytest.raises(FileNotFoundError, match=GRAPH_PATH_ENV):
        reproduce_experiment(TINY_E1, features, tmp_path / "run", device="cpu")


def test_seed_records_the_environment_it_ran_in(features: Path, tmp_path: Path) -> None:
    _write_graph(features / GRAPH_FILENAME)

    result = reproduce_experiment(TINY_E1, features, tmp_path / "run", device="cpu")

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    (record,) = summary["seeds"]
    assert record["environment"]["requested_device"] == "cpu"
    assert record["environment"]["pytorch"]


def test_a_completed_seed_is_reused_on_rerun(features: Path, tmp_path: Path) -> None:
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"
    first = reproduce_experiment(TINY_E1, features, output, device="cpu")
    checkpoint = output / "seed-42" / "best.pt"
    stamp = checkpoint.stat().st_mtime_ns

    second = reproduce_experiment(TINY_E1, features, output, device="cpu")

    assert second.completed_seeds == first.completed_seeds
    assert checkpoint.stat().st_mtime_ns == stamp


def test_swapping_the_graph_is_refused_on_rerun(features: Path, tmp_path: Path) -> None:
    """A different graph means a different topology, so the seeds cannot mix."""
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"
    reproduce_experiment(TINY_E1, features, output, device="cpu")
    rng = np.random.default_rng(7)
    other = tmp_path / "other.npz"
    save_graph(
        other,
        LANDMARK_78_INDICES,
        build_spatial_partitions(rng.uniform(0.0, 1.0, size=(NODE_COUNT, 2))),
    )

    with pytest.raises(ValueError, match="different landmark_graph"):
        reproduce_experiment(TINY_E1, features, output, device="cpu", graph_path=other)


def test_device_index_does_not_split_the_identity(features: Path, tmp_path: Path) -> None:
    """`cuda`/`cuda:2` share a hash; on CPU the same must hold for the recorded one."""
    _write_graph(features / GRAPH_FILENAME)
    output = tmp_path / "run"
    reproduce_experiment(TINY_E1, features, output, device="cpu")

    summary = json.loads((output / "summary.json").read_text(encoding="utf-8"))
    assert summary["configuration"]["device"] == "cpu"
    assert summary["environment"]["requested_device"] == "cpu"
