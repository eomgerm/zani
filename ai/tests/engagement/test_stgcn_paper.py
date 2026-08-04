"""The paper-faithful ST-GCN matches arXiv:2403.17175 where the paper is explicit.

Every number the model's docstring claims is fixed here, including the
parameter-count reconciliation, because that count is the only external check on
whether the architecture was read correctly.
"""

from __future__ import annotations

import numpy as np
import pytest
import torch

from zani_ai.engagement.landmark_graph import (
    NODE_COUNT,
    build_paper_adjacency,
    build_spatial_partitions,
)
from zani_ai.engagement.stgcn import (
    EngagementSTGCN,
    PaperEngagementSTGCN,
    PaperSTGCNConfig,
)

PAPER_NON_ORDINAL_PARAMETERS = 861_688
PAPER_ORDINAL_PARAMETERS = 861_431


def _mean_xy() -> np.ndarray:
    """A face-shaped point cloud: an oval outline plus interior points.

    Deterministic and Delaunay-friendly, without needing the dataset's real mean
    landmark positions.
    """
    angles = np.linspace(0.0, 2.0 * np.pi, 30, endpoint=False)
    outline = np.stack([0.5 + 0.45 * np.cos(angles), 0.5 + 0.6 * np.sin(angles)], axis=1)
    grid = np.linspace(0.25, 0.75, 7)
    interior = np.array([(x, y) for x in grid for y in grid])[: NODE_COUNT - outline.shape[0]]
    return np.concatenate([outline, interior])


def _adjacency() -> torch.Tensor:
    return torch.from_numpy(build_paper_adjacency(_mean_xy()))


def test_adjacency_is_a_single_normalized_graph_with_self_loops() -> None:
    """K=1 with self-loops, against E1's three separately normalized subsets."""
    adjacency = build_paper_adjacency(_mean_xy())

    assert adjacency.shape == (NODE_COUNT, NODE_COUNT)
    assert build_spatial_partitions(_mean_xy()).shape == (3, NODE_COUNT, NODE_COUNT)
    assert np.isfinite(adjacency).all()
    assert np.allclose(adjacency, adjacency.T)
    assert (np.diagonal(adjacency) > 0).all(), "every node needs its self-loop"


def test_adjacency_normalization_matches_the_paper_expression() -> None:
    """`Λ^{-1/2}(A+I)Λ^{-1/2}` with `Λ` taken over `A+I`."""
    mean_xy = _mean_xy()
    adjacency = build_paper_adjacency(mean_xy)

    binary = (adjacency != 0).astype(np.float64)
    degree = binary.sum(axis=1)
    inverse_sqrt = np.power(degree, -0.5)
    expected = (inverse_sqrt[:, None] * binary) * inverse_sqrt[None, :]

    assert np.allclose(adjacency, expected, atol=1e-6)


def test_edge_importance_starts_neutral_and_is_learnable() -> None:
    """`M` is a parameter, initialized to ones so training starts on the plain graph."""
    model = PaperEngagementSTGCN(_adjacency())
    first = model.blocks[0].spatial_conv

    assert first.edge_importance.requires_grad
    assert torch.equal(first.edge_importance, torch.ones(NODE_COUNT, NODE_COUNT))
    matrices = [block.spatial_conv.edge_importance for block in model.blocks]
    assert len({id(matrix) for matrix in matrices}) == len(model.blocks), "M is per layer"


def test_edge_importance_receives_gradient_on_real_edges() -> None:
    """A learnable matrix nothing flows into would be decoration.

    Off-edge entries are multiplied by a zero in the normalized adjacency, so
    they legitimately get zero gradient; the entries that matter must not.
    """
    torch.manual_seed(0)
    adjacency = _adjacency()
    model = PaperEngagementSTGCN(adjacency)
    model(torch.randn(2, 3, 20, NODE_COUNT)).sum().backward()

    gradient = model.blocks[0].spatial_conv.edge_importance.grad
    assert gradient is not None
    on_edge = adjacency != 0
    assert (gradient[on_edge] != 0).any()
    assert torch.equal(gradient[~on_edge], torch.zeros_like(gradient[~on_edge]))


def test_spatial_projection_is_shared_across_the_graph() -> None:
    """One `W_spatial`, not one per partition: `out_channels`, not `out * K`."""
    model = PaperEngagementSTGCN(_adjacency())
    paper_first = model.blocks[0].spatial_conv.conv
    e1_first = EngagementSTGCN(torch.from_numpy(build_spatial_partitions(_mean_xy()))).blocks[
        0
    ].spatial_conv.conv

    assert paper_first.out_channels == 64
    assert e1_first.out_channels == 64 * 3


def test_published_hyperparameters_are_fixed() -> None:
    config = PaperSTGCNConfig()

    assert config.channels == (64, 128, 256)
    assert config.temporal_kernel == 9
    assert config.dropout == 0.1
    assert config.in_channels == 3
    assert config.num_classes == 4

    model = PaperEngagementSTGCN(_adjacency())
    for block in model.blocks:
        assert block.temporal_conv.kernel_size == (9, 1)
        assert block.dropout.p == 0.1
    assert [block.has_residual for block in model.blocks] == [False, True, True]


def test_head_is_a_one_by_one_convolution_equal_to_a_linear_layer() -> None:
    """The paper's form; behaviour must equal E1's `Linear(256, 4)` on pooled input."""
    torch.manual_seed(0)
    model = PaperEngagementSTGCN(_adjacency()).eval()
    pooled = torch.randn(4, 256)

    convolved = model.classifier(pooled[:, :, None, None]).flatten(1)
    linear = torch.nn.functional.linear(
        pooled, model.classifier.weight.flatten(1), model.classifier.bias
    )

    assert torch.allclose(convolved, linear, atol=1e-6)


def test_forward_shape_is_independent_of_sequence_length() -> None:
    """300 steps for the paper, 100 for the deployment contract, same weights."""
    model = PaperEngagementSTGCN(_adjacency()).eval()

    for steps in (100, 300):
        assert model(torch.randn(2, 3, steps, NODE_COUNT)).shape == (2, 4)
    with pytest.raises(ValueError, match="expected input shape"):
        model(torch.randn(2, 3, 300, NODE_COUNT + 1))


def _parameter_count(model: torch.nn.Module) -> int:
    return sum(parameter.numel() for parameter in model.parameters())


def test_parameter_count_reconciles_with_the_paper() -> None:
    """The one external check on whether the architecture was read correctly.

    The reconciliation, not a bare equality: the paper's count matches this
    architecture with the learnable edge-importance matrices excluded, and misses
    by 2.1% with them included.
    """
    model = PaperEngagementSTGCN(_adjacency())
    total = _parameter_count(model)
    edge_importance = sum(
        block.spatial_conv.edge_importance.numel() for block in model.blocks
    )

    assert edge_importance == 3 * NODE_COUNT * NODE_COUNT == 18_252
    assert total == 879_844
    assert total - edge_importance == 861_592

    residual = PAPER_NON_ORDINAL_PARAMETERS - (total - edge_importance)
    assert residual == 96
    assert abs(residual) / PAPER_NON_ORDINAL_PARAMETERS < 0.001, "within the 0.1% allowance"
    assert abs(total - PAPER_NON_ORDINAL_PARAMETERS) / PAPER_NON_ORDINAL_PARAMETERS > 0.02


def test_ordinal_head_explains_the_papers_other_parameter_count() -> None:
    """861,688 - 861,431 = 257 = Conv2d(256,4,1) - Conv2d(256,3,1).

    Independent confirmation that the head is as stated and that everything
    before it is shared between the paper's two variants.
    """
    adjacency = _adjacency()
    non_ordinal = _parameter_count(PaperEngagementSTGCN(adjacency))
    ordinal = _parameter_count(
        PaperEngagementSTGCN(adjacency, PaperSTGCNConfig(num_classes=3))
    )

    assert non_ordinal - ordinal == 257
    assert PAPER_NON_ORDINAL_PARAMETERS - PAPER_ORDINAL_PARAMETERS == 257


def test_existing_e1_model_is_untouched() -> None:
    """E1/E1-A/E1-B checkpoints have to keep loading, so its shape cannot move."""
    model = EngagementSTGCN(torch.from_numpy(build_spatial_partitions(_mean_xy())))

    assert _parameter_count(model) == 942_666
    assert model(torch.randn(2, 3, 100, NODE_COUNT)).shape == (2, 4)
