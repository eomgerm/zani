from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import cast

import torch
from torch import Tensor, nn


@dataclass(frozen=True, slots=True)
class ModelConfig:
    input_dim: int = 98
    segment_count: int = 20
    d_model: int = 256
    nhead: int = 8
    num_layers: int = 4
    mlp_dim: int = 128
    dropout: float = 0.3
    num_classes: int = 4
    head: str = "softmax"  # "softmax" | "coral"

    def __post_init__(self) -> None:
        if self.d_model % self.nhead:
            raise ValueError("d_model must be divisible by nhead")
        if self.head not in ("softmax", "coral"):
            raise ValueError("head must be 'softmax' or 'coral'")

    def to_dict(self) -> dict[str, int | float | str]:
        data = asdict(self)
        if data["head"] == "softmax":
            # Omit when default so the E0/E0-A configuration dict (and its
            # frozen hash) stays byte-identical to the pre-CORAL schema.
            del data["head"]
        return data


class EngagementTransformer(nn.Module):
    """Paper-based temporal Transformer over 20 MediaPipe feature tokens."""

    feature_mean: Tensor
    feature_std: Tensor

    def __init__(
        self,
        feature_mean: Tensor,
        feature_std: Tensor,
        *,
        config: ModelConfig | None = None,
    ) -> None:
        super().__init__()
        self.config = config or ModelConfig()
        if feature_mean.shape != (self.config.input_dim,):
            raise ValueError(f"feature_mean must have shape ({self.config.input_dim},)")
        if feature_std.shape != (self.config.input_dim,):
            raise ValueError(f"feature_std must have shape ({self.config.input_dim},)")
        self.register_buffer("feature_mean", feature_mean.float().reshape(1, 1, -1))
        self.register_buffer("feature_std", feature_std.float().clamp_min(1e-6).reshape(1, 1, -1))
        self.input_projection = nn.Linear(self.config.input_dim, self.config.d_model)
        self.position_embedding = nn.Parameter(
            torch.zeros(1, self.config.segment_count, self.config.d_model)
        )
        nn.init.normal_(self.position_embedding, std=0.02)
        layer = nn.TransformerEncoderLayer(
            d_model=self.config.d_model,
            nhead=self.config.nhead,
            dim_feedforward=self.config.d_model * 4,
            dropout=self.config.dropout,
            activation="gelu",
            batch_first=True,
            norm_first=True,
        )
        self.encoder = nn.TransformerEncoder(
            layer, num_layers=self.config.num_layers, enable_nested_tensor=False
        )
        if self.config.head == "softmax":
            # Unchanged submodule structure/keys so existing E0/E0-A
            # checkpoints (state_dict keys `classifier.0.*`, `classifier.3.*`)
            # still load.
            self.classifier = nn.Sequential(
                nn.Linear(self.config.d_model, self.config.mlp_dim),
                nn.ReLU(),
                nn.Dropout(self.config.dropout),
                nn.Linear(self.config.mlp_dim, self.config.num_classes),
            )
        else:  # coral
            self.classifier_shared = nn.Sequential(
                nn.Linear(self.config.d_model, self.config.mlp_dim),
                nn.ReLU(),
                nn.Dropout(self.config.dropout),
            )
            self.coral_fc = nn.Linear(self.config.mlp_dim, 1)
            self.coral_bias = nn.Parameter(torch.zeros(self.config.num_classes - 1))

    def forward(self, tokens: Tensor) -> Tensor:
        normalized = (tokens - self.feature_mean) / self.feature_std
        embedded = self.input_projection(normalized) + self.position_embedding
        encoded = self.encoder(embedded)
        pooled = encoded.amax(dim=1)
        if self.config.head == "softmax":
            return cast(Tensor, self.classifier(pooled))
        feat = self.classifier_shared(pooled)
        return cast(Tensor, self.coral_fc(feat) + self.coral_bias)


__all__ = ["EngagementTransformer", "ModelConfig"]
