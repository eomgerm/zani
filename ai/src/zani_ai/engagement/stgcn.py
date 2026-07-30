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


__all__ = ["EngagementSTGCN", "STGCNBlock", "STGCNConfig", "SpatialGraphConv"]
