"""E1-P is wired to its own model, input and export metadata.

Two of these failures would not raise -- the run would finish and report numbers
from the wrong model or ship a model with the wrong declared input. Those are the
ones worth a test.
"""

from __future__ import annotations

import numpy as np
import pytest
import torch

from zani_ai.engagement.experiment import (
    SPECS,
    paper_stgcn_model_builder,
    stgcn_model_builder,
)
from zani_ai.engagement.export import DeploymentMetadata
from zani_ai.engagement.landmark_graph import (
    NODE_COUNT,
    build_spatial_partitions,
    paper_adjacency_from_partitions,
    save_graph,
)
from zani_ai.engagement.stgcn import (
    EngagementSTGCN,
    PaperEngagementSTGCN,
    PaperSTGCNConfig,
)
from zani_ai.engagement.training import (
    EvaluationMetrics,
    TrainingConfig,
    _save_checkpoint,
    load_checkpoint,
)

E1P = SPECS["E1-P"]


def _metrics() -> EvaluationMetrics:
    return EvaluationMetrics(
        accuracy=0.5,
        macro_f1=0.4,
        within_one_accuracy=0.9,
        quadratic_weighted_kappa=0.3,
        confusion_matrix=[[1, 0, 0, 0]] * 4,
        classification_report={},
    )


def _mean_xy() -> np.ndarray:
    angles = np.linspace(0.0, 2.0 * np.pi, 30, endpoint=False)
    outline = np.stack([0.5 + 0.45 * np.cos(angles), 0.5 + 0.6 * np.sin(angles)], axis=1)
    grid = np.linspace(0.25, 0.75, 7)
    interior = np.array([(x, y) for x in grid for y in grid])[: NODE_COUNT - outline.shape[0]]
    return np.concatenate([outline, interior])


def test_spec_matches_the_papers_published_training_recipe() -> None:
    """Adam, batch 16, lr 1e-3, 300 epochs, decay at 100 and 200."""
    assert E1P.learning_rate == 1e-3
    assert E1P.batch_size == 16
    assert E1P.max_epochs == 300
    assert E1P.patience == E1P.max_epochs, "the paper runs the full budget"
    assert E1P.lr_step == 100, "StepLR(100, 0.1) decays at both 100 and 200 of 300"


def test_spec_reads_the_placeholder_thirty_fps_representation() -> None:
    assert E1P.schema_name == "landmark_78_300_placeholder_v1"
    assert E1P.array_shape == (3, 300, 78)
    assert E1P.array_key == "sequence"
    assert not E1P.needs_feature_stats


def test_spec_uses_the_default_seed_list() -> None:
    """The paper reports one accuracy with no seed count, so the spread matters."""
    assert len(E1P.seeds) == 10
    assert E1P.seeds != SPECS["E1"].seeds


def test_existing_e1_protocols_keep_their_identity() -> None:
    """Adding E1-P must not move E1/E1-A/E1-B, whose artifacts already exist."""
    for protocol, representation, shape in (
        ("E1", "landmark_78_v1", (3, 100, 78)),
        ("E1-A", "landmark_78_v1", (3, 100, 78)),
        ("E1-B", "landmark_78_300_v1", (3, 300, 78)),
    ):
        spec = SPECS[protocol]
        assert spec.schema_name == representation
        assert spec.array_shape == shape
        assert len(spec.seeds) == 5


def test_graph_rebind_picks_the_model_the_spec_declares(tmp_path) -> None:
    """The silent failure: both families need a graph, so one rebind fits both.

    `reproduce_experiment` replaces `build_model` after resolving the graph file.
    Keying that off `needs_landmark_graph` alone trains E1-P with E1's
    3-partition model -- no error, just wrong numbers.
    """
    graph = tmp_path / "landmark_78_v1_graph.npz"
    save_graph(graph, tuple(range(NODE_COUNT)), build_spatial_partitions(_mean_xy()))

    assert isinstance(paper_stgcn_model_builder(graph)(), PaperEngagementSTGCN)
    assert isinstance(stgcn_model_builder(graph)(), EngagementSTGCN)
    assert isinstance(E1P.model_config, PaperSTGCNConfig)
    assert not isinstance(SPECS["E1"].model_config, PaperSTGCNConfig)


def test_builder_reconstructs_the_paper_graph_from_the_saved_partitions() -> None:
    """The saved file holds 3 partitions; their union recovers `A`, root is `I`."""
    partitions = build_spatial_partitions(_mean_xy())
    adjacency = paper_adjacency_from_partitions(partitions)

    assert adjacency.shape == (NODE_COUNT, NODE_COUNT)
    edges = (partitions[1] != 0) | (partitions[2] != 0)
    expected_nonzero = edges | np.eye(NODE_COUNT, dtype=bool)
    assert np.array_equal(adjacency != 0, expected_nonzero)
    with pytest.raises(ValueError, match="expected partitions shape"):
        paper_adjacency_from_partitions(partitions[0])


def test_export_metadata_declares_the_step_count_it_was_trained_at() -> None:
    """The other silent failure: a 300-step model shipped as a 100-step one.

    The browser validates metadata, not the graph, so it would load the model and
    then feed it a tensor of the wrong length.
    """
    paper = DeploymentMetadata.for_stgcn("landmark_78_300_placeholder_v1", steps=300)

    assert paper.input_shape == ("batch", 3, 300, 78)
    assert paper.segment_count == 300
    assert paper.sample_fps == 30.0

    e1 = DeploymentMetadata.for_stgcn()
    assert e1.schema == "landmark_78_v1"
    assert e1.input_shape == ("batch", 3, 100, 78)
    assert e1.segment_count == 100
    assert e1.sample_fps == 10.0


def test_checkpoint_round_trips_the_paper_model(tmp_path) -> None:
    """The checkpoint has to carry its own graph, and come back as the same model.

    `_save_checkpoint` stores an ST-GCN's fixed graph buffer explicitly, and the
    two families hold different ones -- E1's `[3,V,V]` partitions under
    `partitions`, the paper's `[V,V]` `A+I` under `adjacency`. Reading the wrong
    key rebuilds the wrong class, and `model_family` is "stgcn" for both, so the
    stored key is the only discriminator.
    """
    graph = tmp_path / "landmark_78_v1_graph.npz"
    save_graph(graph, tuple(range(NODE_COUNT)), build_spatial_partitions(_mean_xy()))
    model = paper_stgcn_model_builder(graph)()
    checkpoint = tmp_path / "best.pt"
    _save_checkpoint(
        checkpoint,
        model,
        TrainingConfig(features_root=tmp_path, output_dir=tmp_path),
        None,
        epoch=0,
        validation=_metrics(),
        schema=E1P.schema_name,
    )

    payload = torch.load(checkpoint, map_location="cpu", weights_only=False)
    assert "adjacency" in payload
    assert "partitions" not in payload
    assert payload["adjacency"].shape == (NODE_COUNT, NODE_COUNT)

    restored = load_checkpoint(checkpoint)
    assert isinstance(restored, PaperEngagementSTGCN)
    for original, loaded in zip(
        model.state_dict().values(), restored.state_dict().values(), strict=True
    ):
        assert torch.equal(original, loaded)


def test_checkpoint_round_trips_the_e1_model(tmp_path) -> None:
    """The same path must still rebuild E1, whose checkpoints already exist."""
    graph = tmp_path / "landmark_78_v1_graph.npz"
    save_graph(graph, tuple(range(NODE_COUNT)), build_spatial_partitions(_mean_xy()))
    model = stgcn_model_builder(graph)()
    checkpoint = tmp_path / "best.pt"
    _save_checkpoint(
        checkpoint,
        model,
        TrainingConfig(features_root=tmp_path, output_dir=tmp_path),
        None,
        epoch=0,
        validation=_metrics(),
        schema="landmark_78_v1",
    )

    payload = torch.load(checkpoint, map_location="cpu", weights_only=False)
    assert "partitions" in payload
    assert "adjacency" not in payload
    assert isinstance(load_checkpoint(checkpoint), EngagementSTGCN)


def test_exported_paper_model_runs_at_its_declared_shape(tmp_path) -> None:
    graph = tmp_path / "landmark_78_v1_graph.npz"
    save_graph(graph, tuple(range(NODE_COUNT)), build_spatial_partitions(_mean_xy()))
    model = paper_stgcn_model_builder(graph)()
    metadata = DeploymentMetadata.for_stgcn(E1P.schema_name, steps=300)
    batch, channels, steps, nodes = (2, *metadata.input_shape[1:])

    with torch.no_grad():
        logits = model(torch.zeros(batch, channels, steps, nodes))

    assert logits.shape == (batch, len(metadata.labels))
