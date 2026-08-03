"""E1 ST-GCN model: engagement classification over 78-node landmark graphs.

`landmark_graph.py` (Task 1) builds the fixed spatial-configuration adjacency
`partitions[3,78,78]` (root / centripetal / centrifugal, Yan, Xiong & Lin,
"Spatial Temporal Graph Convolutional Networks for Skeleton-Based Action
Recognition", AAAI 2018). `representations.py` (Task 2) builds one
`[3,100,78]` (channel, time, node) landmark-sequence tensor per clip. This
module is the standard spatial-configuration ST-GCN backbone consuming that
sequence and producing 4-class engagement logits.

Architecture (`EngagementSTGCN.forward`, `[B,3,100,78] -> [B,4]`):

1. Input normalization: `nn.BatchNorm2d(in_channels)` applied directly to
   `[B, C, T, V]`. This normalizes per input channel (x/y/z-like) across the
   batch/time/node dims, mirroring how every downstream `Conv2d` already
   treats channels as the normalized axis -- no reshape to `[B, C*V]` is
   needed, unlike a `BatchNorm1d(in_channels*num_nodes)` alternative (also
   standard in some ST-GCN implementations), which additionally normalizes
   per-node and would require a transpose/reshape/un-reshape around it. Both
   are valid; the `BatchNorm2d` form is picked here for the simpler,
   allocation-free forward pass.
2. Three `STGCNBlock`s (`3 -> 64 -> 128 -> 256` channels, one block per entry
   in `STGCNConfig.channels`), each:
   a. Spatial graph convolution (`SpatialGraphConv`): a single `Conv2d(in,
      out*K, kernel_size=1)` projects each frame independently to `K =
      num_partitions` candidate feature maps, which are reshaped to
      `[B, K, out, T, V]` and combined with the fixed `partitions[K,V,W]`
      buffer via `torch.einsum('bkctv,kvw->bctw', ...)` -- i.e. each
      partition's normalized adjacency aggregates its own learned projection
      over neighboring nodes, and the three partitions' results are summed.
      This is the standard ST-GCN spatial-configuration aggregation and uses
      only a 1x1 conv + einsum (no scatter/index_add), so it stays exact
      under `torch.use_deterministic_algorithms(True)`.
   b. Temporal convolution: `Conv2d(out, out, kernel_size=(temporal_kernel,
      1), padding=((temporal_kernel-1)//2, 0))`, sliding over the time axis
      only (kernel width 1 over nodes), same-padded so `T` is preserved.
   c. `BatchNorm2d(out)`.
   d. Residual add (blocks 2 and 3 only): when enabled, `nn.Identity()` when
      `in_channels == out_channels`, otherwise a `Conv2d(in_channels,
      out_channels, kernel_size=1)` projection of the block's input -- added
      to the normalized temporal output *before* the final ReLU (standard
      ST-GCN residual placement). The first block (block 1, 3 -> 64 channels)
      has no residual path; residuals are applied only to blocks 2 and 3
      (64 -> 128 -> 256).
   e. ReLU, then `Dropout(dropout)`.
3. Global average pool over both time and node axes (`x.mean(dim=(2, 3))`,
   a plain reduction -- deterministic and allocation-light) -> `[B, 256]`.
4. `nn.Linear(256, num_classes)` -> `[B, num_classes]` logits.

`partitions` is registered as a `buffer` (not a parameter): it is a fixed,
non-learned graph fixed by `landmark_graph.py`, but must still move with
`.to(device/dtype)` and be included in `state_dict()`/checkpoints so a saved
model is self-contained and doesn't depend on recomputing the graph.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import cast

import torch
from torch import Tensor, nn


@dataclass(frozen=True, slots=True)
class STGCNConfig:
    in_channels: int = 3
    num_nodes: int = 78
    num_partitions: int = 3
    channels: tuple[int, ...] = (64, 128, 256)
    temporal_kernel: int = 9
    dropout: float = 0.1
    num_classes: int = 4

    def __post_init__(self) -> None:
        if self.temporal_kernel % 2 == 0:
            raise ValueError("temporal_kernel must be odd for same-padding")
        if not self.channels:
            raise ValueError("channels must be non-empty")

    def to_dict(self) -> dict[str, int | float | str | list[int]]:
        data = asdict(self)
        data["channels"] = list(data["channels"])
        return data


class SpatialGraphConv(nn.Module):
    """Spatial-configuration graph convolution over `num_partitions` subsets.

    A single `1x1` `Conv2d` produces `num_partitions` candidate per-node
    feature maps at once (`out_channels * num_partitions` output channels),
    which are then aggregated per partition against that partition's fixed,
    pre-normalized adjacency (`partitions[k]`) and summed -- the standard
    ST-GCN spatial-configuration partitioning strategy (Yan et al., 2018).
    """

    def __init__(self, in_channels: int, out_channels: int, num_partitions: int) -> None:
        super().__init__()
        self.num_partitions = num_partitions
        self.out_channels = out_channels
        self.conv = nn.Conv2d(in_channels, out_channels * num_partitions, kernel_size=1)

    def forward(self, x: Tensor, partitions: Tensor) -> Tensor:
        batch, _, time, nodes = x.shape
        projected = self.conv(x)  # [B, K*out, T, V]
        projected = projected.view(batch, self.num_partitions, self.out_channels, time, nodes)
        return cast(Tensor, torch.einsum("bkctv,kvw->bctw", projected, partitions))


class STGCNBlock(nn.Module):
    """One spatial-graph-conv + temporal-conv ST-GCN block with optional residual."""

    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        num_partitions: int,
        temporal_kernel: int,
        dropout: float,
        residual: bool = True,
    ) -> None:
        super().__init__()
        self.spatial_conv = SpatialGraphConv(in_channels, out_channels, num_partitions)
        padding = (temporal_kernel - 1) // 2
        self.temporal_conv = nn.Conv2d(
            out_channels, out_channels, kernel_size=(temporal_kernel, 1), padding=(padding, 0)
        )
        self.bn = nn.BatchNorm2d(out_channels)
        self.relu = nn.ReLU(inplace=True)
        self.dropout = nn.Dropout(dropout)
        self.has_residual = residual
        self.residual: nn.Module | None
        if residual:
            if in_channels == out_channels:
                self.residual = nn.Identity()
            else:
                self.residual = nn.Conv2d(in_channels, out_channels, kernel_size=1)
        else:
            self.residual = None

    def forward(self, x: Tensor, partitions: Tensor) -> Tensor:
        out = self.spatial_conv(x, partitions)
        out = self.temporal_conv(out)
        out = self.bn(out)
        if self.has_residual and self.residual is not None:
            residual = self.residual(x)
            out = out + residual
        out = self.relu(out)
        out = self.dropout(out)
        return out


class EngagementSTGCN(nn.Module):
    """Spatial-configuration ST-GCN over `[B, 3, 100, 78]` landmark sequences."""

    partitions: Tensor

    def __init__(self, partitions: Tensor, config: STGCNConfig | None = None) -> None:
        super().__init__()
        self.config = config or STGCNConfig()
        expected_shape = (
            self.config.num_partitions,
            self.config.num_nodes,
            self.config.num_nodes,
        )
        if tuple(partitions.shape) != expected_shape:
            raise ValueError(
                f"partitions must have shape {expected_shape}, got {tuple(partitions.shape)}"
            )
        self.register_buffer("partitions", partitions.float())

        self.input_bn = nn.BatchNorm2d(self.config.in_channels)

        channel_sequence = (self.config.in_channels, *self.config.channels)
        self.blocks = nn.ModuleList(
            [
                STGCNBlock(
                    channel_sequence[i],
                    channel_sequence[i + 1],
                    self.config.num_partitions,
                    self.config.temporal_kernel,
                    self.config.dropout,
                    residual=(i >= 1),
                )
                for i in range(len(self.config.channels))
            ]
        )
        self.classifier = nn.Linear(self.config.channels[-1], self.config.num_classes)

    def forward(self, x: Tensor) -> Tensor:
        if (
            x.dim() != 4
            or x.shape[1] != self.config.in_channels
            or x.shape[3] != self.config.num_nodes
        ):
            raise ValueError(
                f"expected input shape [B, {self.config.in_channels}, T, {self.config.num_nodes}], "
                f"got {tuple(x.shape)}"
            )
        out = self.input_bn(x)
        for block in self.blocks:
            out = block(out, self.partitions)
        pooled = out.mean(dim=(2, 3))
        return cast(Tensor, self.classifier(pooled))


@dataclass(frozen=True, slots=True)
class PaperSTGCNConfig:
    """Architecture of arXiv:2403.17175's non-ordinal ST-GCN.

    No `num_partitions`: the paper uses a single `A+I` adjacency (K=1), so
    there is nothing to partition. `num_classes` is 4 for the non-ordinal model
    and K-1 = 3 for the ordinal variant, which is the only difference between
    the two -- see `PaperEngagementSTGCN`'s parameter-count note.
    """

    in_channels: int = 3
    num_nodes: int = 78
    channels: tuple[int, ...] = (64, 128, 256)
    temporal_kernel: int = 9
    dropout: float = 0.1
    num_classes: int = 4

    def __post_init__(self) -> None:
        if self.temporal_kernel % 2 == 0:
            raise ValueError("temporal_kernel must be odd for same-padding")
        if not self.channels:
            raise ValueError("channels must be non-empty")

    def to_dict(self) -> dict[str, int | float | str | list[int]]:
        data = asdict(self)
        data["channels"] = list(data["channels"])
        return data


class PaperSpatialGraphConv(nn.Module):
    """Shared 1x1 projection against one learnable `(A+I)⊙M` adjacency.

    Differs from `SpatialGraphConv` in both published respects. The projection
    is a single `W_spatial` rather than one per partition, so the 1x1 conv
    produces `out_channels` maps instead of `out_channels * K`. And the
    adjacency carries a learnable edge-importance matrix `M`, initialized to
    ones so training starts from the plain normalized graph.

    `adjacency` arrives pre-normalized (`landmark_graph.build_paper_adjacency`),
    and `M` multiplies it elementwise -- identical to the paper's
    `Λ^{-1/2}((A+I)⊙M)Λ^{-1/2}` because diagonal scaling commutes with the
    elementwise product.

    `M` is dense `[V, V]` rather than confined to `A+I`'s nonzeros. Off-edge
    entries are multiplied by a zero in the normalized adjacency, so they can
    never affect the output; keeping the parameter dense follows the canonical
    implementation (`nn.Parameter(torch.ones(A.size()))`) and avoids a scatter.
    Those entries do receive zero gradient, which `test_stgcn_paper.py` fixes.
    """

    def __init__(self, in_channels: int, out_channels: int, num_nodes: int) -> None:
        super().__init__()
        self.conv = nn.Conv2d(in_channels, out_channels, kernel_size=1)
        self.edge_importance = nn.Parameter(torch.ones(num_nodes, num_nodes))

    def forward(self, x: Tensor, adjacency: Tensor) -> Tensor:
        weighted = adjacency * self.edge_importance
        return torch.einsum("bctv,vw->bctw", self.conv(x), weighted)


class PaperSTGCNBlock(nn.Module):
    """One ST-GCN layer in canonical order, with the paper's spatial convolution.

    Operation order follows the canonical implementation (yysijie/st-gcn), which
    the paper does not spell out: spatial graph convolution, then batch norm and
    ReLU, then the temporal convolution, then batch norm, then the residual add,
    then ReLU and dropout. Two batch norms per layer, not one -- this is where
    the reproduction differs from `STGCNBlock`, which has a single one.

    The residual is `Conv2d(in, out, 1)` followed by `BatchNorm2d(out)` when the
    channel count changes, again per the canonical implementation, and
    `nn.Identity()` when it does not.
    """

    def __init__(
        self,
        in_channels: int,
        out_channels: int,
        num_nodes: int,
        temporal_kernel: int,
        dropout: float,
        residual: bool = True,
    ) -> None:
        super().__init__()
        self.spatial_conv = PaperSpatialGraphConv(in_channels, out_channels, num_nodes)
        self.spatial_bn = nn.BatchNorm2d(out_channels)
        padding = (temporal_kernel - 1) // 2
        self.temporal_conv = nn.Conv2d(
            out_channels, out_channels, kernel_size=(temporal_kernel, 1), padding=(padding, 0)
        )
        self.temporal_bn = nn.BatchNorm2d(out_channels)
        self.relu = nn.ReLU(inplace=True)
        self.dropout = nn.Dropout(dropout)
        self.has_residual = residual
        self.residual: nn.Module | None
        if not residual:
            self.residual = None
        elif in_channels == out_channels:
            self.residual = nn.Identity()
        else:
            self.residual = nn.Sequential(
                nn.Conv2d(in_channels, out_channels, kernel_size=1),
                nn.BatchNorm2d(out_channels),
            )

    def forward(self, x: Tensor, adjacency: Tensor) -> Tensor:
        out = self.spatial_conv(x, adjacency)
        out = self.relu(self.spatial_bn(out))
        out = self.temporal_bn(self.temporal_conv(out))
        if self.has_residual and self.residual is not None:
            out = out + self.residual(x)
        return cast(Tensor, self.dropout(self.relu(out)))


class PaperEngagementSTGCN(nn.Module):
    """arXiv:2403.17175's non-ordinal ST-GCN over `[B, 3, T, 78]` sequences.

    Input normalization is `BatchNorm1d(in_channels * num_nodes)` over the
    flattened `[B, C*V, T]` view -- the canonical implementation's `data_bn`.
    The paper says only "input batch normalization"; this form is picked because
    it is the one the canonical reference uses and because the parameter count
    below only reconciles with the paper under it.

    Classification is average pooling over time and nodes followed by
    `Conv2d(256, num_classes, 1x1)`, the paper's stated form. Mathematically a
    1x1 convolution on a pooled `[B, C, 1, 1]` tensor equals `Linear(C, n)`;
    `test_stgcn_paper.py` fixes that equivalence so the difference from
    `EngagementSTGCN`'s `Linear` head stays a matter of form, not behaviour.

    Parameter count (`test_stgcn_paper.py` fixes every number here):

    ==========================================  =========
    this model, 4 classes                         879,844
    minus the three learnable `M` (3 x 78 x 78)  -18,252
    ------------------------------------------  ---------
    architecture without edge importance          861,592
    the paper's reported non-ordinal count        861,688
    residual                                           96
    ==========================================  =========

    So the paper's number reconciles to 0.011% with the learnable
    edge-importance matrices *excluded* from the count, and misses by 2.1% with
    them included. Two independent checks support reading it that way. The
    paper's own ordinal count, 861,431, differs from its non-ordinal count by
    exactly 257, which is `Conv2d(256,4,1)` minus `Conv2d(256,3,1)` -- so the
    head is as stated, everything else is shared between the two variants, and
    the rest of the architecture is fixed. And no structural reading of the
    published details lands on 861,688 with a per-layer `M` sized to `A+I`'s
    nonzeros: the closest demands 442 entries per layer where a 78-node face
    triangulation yields about 494.

    The remaining 96 sits in details the paper does not publish. It is kept as a
    recorded residual rather than closed by fitting flags to the target, because
    a count reverse-engineered to match is not evidence of a match.
    """

    adjacency: Tensor

    def __init__(self, adjacency: Tensor, config: PaperSTGCNConfig | None = None) -> None:
        super().__init__()
        self.config = config or PaperSTGCNConfig()
        expected_shape = (self.config.num_nodes, self.config.num_nodes)
        if tuple(adjacency.shape) != expected_shape:
            raise ValueError(
                f"adjacency must have shape {expected_shape}, got {tuple(adjacency.shape)}"
            )
        self.register_buffer("adjacency", adjacency.float())

        self.input_bn = nn.BatchNorm1d(self.config.in_channels * self.config.num_nodes)
        channel_sequence = (self.config.in_channels, *self.config.channels)
        self.blocks = nn.ModuleList(
            [
                PaperSTGCNBlock(
                    channel_sequence[index],
                    channel_sequence[index + 1],
                    self.config.num_nodes,
                    self.config.temporal_kernel,
                    self.config.dropout,
                    residual=(index >= 1),
                )
                for index in range(len(self.config.channels))
            ]
        )
        self.classifier = nn.Conv2d(self.config.channels[-1], self.config.num_classes, 1)

    def forward(self, x: Tensor) -> Tensor:
        if (
            x.dim() != 4
            or x.shape[1] != self.config.in_channels
            or x.shape[3] != self.config.num_nodes
        ):
            raise ValueError(
                f"expected input shape [B, {self.config.in_channels}, T, {self.config.num_nodes}], "
                f"got {tuple(x.shape)}"
            )
        batch, channels, steps, nodes = x.shape
        # [B,C,T,V] -> [B,C*V,T] for data_bn, then back. permute before reshape:
        # the flattened axis has to be (channel, node), which is the layout the
        # BatchNorm1d feature count describes.
        out = x.permute(0, 1, 3, 2).reshape(batch, channels * nodes, steps)
        out = self.input_bn(out)
        out = out.view(batch, channels, nodes, steps).permute(0, 1, 3, 2).contiguous()
        for block in self.blocks:
            out = block(out, self.adjacency)
        pooled = out.mean(dim=(2, 3), keepdim=True)
        return cast(Tensor, self.classifier(pooled).flatten(1))


__all__ = [
    "EngagementSTGCN",
    "PaperEngagementSTGCN",
    "PaperSTGCNBlock",
    "PaperSTGCNConfig",
    "PaperSpatialGraphConv",
    "STGCNBlock",
    "STGCNConfig",
    "SpatialGraphConv",
]
