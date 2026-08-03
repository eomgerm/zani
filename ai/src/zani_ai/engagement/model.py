from __future__ import annotations

from dataclasses import asdict, dataclass
from typing import Self, cast

import torch
from torch import Tensor, nn

#: Classifier heads. ``coral`` shares one weight vector across the K-1
#: thresholds and varies only the bias, so its cumulative logits are monotone by
#: construction. ``ordinal_binary`` gives every threshold its own head and shares
#: nothing, which is what makes the monotonicity repair below necessary.
HEADS = ("softmax", "coral", "ordinal_binary")


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
    head: str = "softmax"  # see HEADS

    def __post_init__(self) -> None:
        if self.d_model % self.nhead:
            raise ValueError("d_model must be divisible by nhead")
        if self.head not in HEADS:
            raise ValueError(f"head must be one of {HEADS}, got {self.head!r}")

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
        # Set before any submodule exists so `train()` is safe from the start.
        self._backbone_frozen = False
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
        elif self.config.head == "ordinal_binary":
            # K-1 independent binary heads, each a copy of the softmax head's
            # shape down to a single logit. Nothing is shared between them --
            # that independence is exactly what separates this from CORAL.
            self.ordinal_heads = nn.ModuleList(
                nn.Sequential(
                    nn.Linear(self.config.d_model, self.config.mlp_dim),
                    nn.ReLU(),
                    nn.Dropout(self.config.dropout),
                    nn.Linear(self.config.mlp_dim, 1),
                )
                for _ in range(self.config.num_classes - 1)
            )
        else:  # coral
            self.classifier_shared = nn.Sequential(
                nn.Linear(self.config.d_model, self.config.mlp_dim),
                nn.ReLU(),
                nn.Dropout(self.config.dropout),
            )
            self.coral_fc = nn.Linear(self.config.mlp_dim, 1)
            self.coral_bias = nn.Parameter(torch.zeros(self.config.num_classes - 1))

    def backbone_modules(self) -> tuple[nn.Module, ...]:
        """The shared representation: everything before the classifier head."""
        return (self.input_projection, self.encoder)

    def freeze_backbone(self) -> None:
        """Stop training the representation and keep it out of training mode.

        Two-stage protocols (E0-L) train only the head, so the backbone must be
        fixed in both senses: no gradients, and no dropout. Leaving it in
        training mode would resample encoder dropout every batch, and a head
        fitted against a representation that moves is not a head fitted against
        a frozen one.
        """
        self._backbone_frozen = True
        self.position_embedding.requires_grad_(False)
        for module in self.backbone_modules():
            module.requires_grad_(False)
        for module in self.backbone_modules():
            module.eval()

    def train(self, mode: bool = True) -> Self:
        super().train(mode)
        if self._backbone_frozen:
            # `super().train()` recurses into every child, so the backbone has
            # to be put back afterwards rather than merely skipped.
            for child in self.backbone_modules():
                child.eval()
        return self

    def forward(self, tokens: Tensor) -> Tensor:
        normalized = (tokens - self.feature_mean) / self.feature_std
        embedded = self.input_projection(normalized) + self.position_embedding
        encoded = self.encoder(embedded)
        pooled = encoded.amax(dim=1)
        if self.config.head == "softmax":
            return cast(Tensor, self.classifier(pooled))
        if self.config.head == "ordinal_binary":
            return torch.cat([head(pooled) for head in self.ordinal_heads], dim=1)
        feat = self.classifier_shared(pooled)
        return cast(Tensor, self.coral_fc(feat) + self.coral_bias)


def monotone_cumulative_probabilities(logits: Tensor) -> Tensor:
    """``sigmoid`` of K-1 independent threshold logits, projected onto monotone.

    Independent heads do not guarantee ``P(y>0) >= P(y>1) >= P(y>2)``, so the
    running minimum ``p̃_j = min(p_0..p_j)`` is applied before anything reads the
    values as cumulative probabilities. That projection is what makes the class
    probabilities below non-negative by construction rather than by clamping.

    Written as an unrolled Python loop over the *static* threshold count rather
    than ``torch.cummin``: the loop traces to a chain of ONNX ``Min`` nodes,
    while ``cummin`` has no ONNX counterpart and would land as a ``Scan``. The
    loop bound is the class count, which is fixed per model, so nothing about
    the exported shapes depends on it.
    """
    probabilities = torch.sigmoid(logits)
    columns = [probabilities[:, :1]]
    for index in range(1, probabilities.shape[1]):
        columns.append(torch.minimum(columns[-1], probabilities[:, index : index + 1]))
    return torch.cat(columns, dim=1)


def ordinal_binary_class_probabilities(logits: Tensor) -> Tensor:
    """``[B, K]`` class probabilities from ``[B, K-1]`` independent binary heads.

    Adjacent differences of the monotone cumulative probabilities. The clamp and
    the normalization only absorb float error -- the projection above already
    guarantees non-negative differences summing to one.
    """
    cumulative = monotone_cumulative_probabilities(logits)
    first = 1 - cumulative[:, :1]
    middle = cumulative[:, :-1] - cumulative[:, 1:]
    last = cumulative[:, -1:]
    probabilities = torch.cat([first, middle, last], dim=1).clamp_min(0)
    return probabilities / probabilities.sum(dim=1, keepdim=True)


__all__ = [
    "HEADS",
    "EngagementTransformer",
    "ModelConfig",
    "monotone_cumulative_probabilities",
    "ordinal_binary_class_probabilities",
]
