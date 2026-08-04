from __future__ import annotations

from dataclasses import asdict, dataclass, replace
from typing import Self, cast

import torch
from torch import Tensor, nn

#: Classifier heads. ``coral`` shares one weight vector across the K-1
#: thresholds and varies only the bias, so its cumulative logits are monotone by
#: construction. ``ordinal_binary`` gives every threshold its own head and shares
#: nothing, which is what makes the monotonicity repair below necessary.
#: ``dual_softmax_ordinal`` carries both a softmax head and an ordinal one over
#: one encoder pass; see :class:`DualHeadMixture` for how their outputs combine.
HEADS = ("softmax", "coral", "ordinal_binary", "dual_softmax_ordinal")

#: The head whose two sub-heads are mixed at inference time. Named because three
#: modules branch on it and a string literal repeated four times is how one of
#: them ends up spelled differently.
DUAL_HEAD = "dual_softmax_ordinal"


@dataclass(frozen=True, slots=True)
class ProbabilityMixing:
    """One selected mixing point: what the deployed dual-head model computes.

    Per-seed, chosen on Validation after the ordinal head is trained, so it is
    part of the artifact rather than of the protocol identity (the *grid* it was
    chosen from is the identity -- see :class:`MixingProtocol`).

    ``alpha`` weights the softmax half; ``alpha = 1`` is the stage-1 baseline's
    own decision and ``alpha = 0`` is the ordinal head alone. The two
    temperatures are separate because the halves are differently calibrated:
    the softmax head was fitted with a cross-entropy loss on 4 classes and the
    ordinal one with a BCE on 3 thresholds.
    """

    alpha: float
    temperature_softmax: float
    temperature_ordinal: float
    epsilon: float

    def __post_init__(self) -> None:
        if not 0.0 <= self.alpha <= 1.0:
            raise ValueError(f"alpha must be in [0, 1], got {self.alpha!r}")
        for name in ("temperature_softmax", "temperature_ordinal"):
            if getattr(self, name) <= 0:
                raise ValueError(f"{name} must be positive, got {getattr(self, name)!r}")
        if not 0 < self.epsilon < 0.25:
            # 0.25 is where the clamp alone would already flatten a 4-class
            # distribution to uniform, so a value at or above it is a mistake.
            raise ValueError(f"epsilon must be in (0, 0.25), got {self.epsilon!r}")

    def to_dict(self) -> dict[str, float]:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class MixingProtocol:
    """The pre-registered grid and the rule that picks one point out of it.

    All of it enters the reproducibility identity: a grid searched on Validation
    is part of the experiment, and a selection rule chosen after seeing the
    numbers is not a protocol. The two budgets are the deployment gate restated
    on Validation -- false alarms a lesson can absorb, and how much 4-class
    accuracy may be traded for detection.
    """

    alpha_grid: tuple[float, ...] = (0.0, 0.25, 0.5, 0.75, 1.0)
    temperature_grid: tuple[float, ...] = (1.0, 1.5, 2.0)
    epsilon: float = 1e-6
    #: Judged on the independence approximation in
    #: `contracts.low_engagement_metrics`, matching the deployment gate.
    false_alarm_budget_per_90min: float = 0.5
    #: Largest 4-class accuracy drop against the ``alpha = 1`` corner.
    accuracy_drop_budget: float = 0.01

    def __post_init__(self) -> None:
        if not self.alpha_grid or not self.temperature_grid:
            raise ValueError("the mixing grid must not be empty")
        if 1.0 not in self.alpha_grid:
            # The reference the accuracy guard is measured against, and the
            # fallback when nothing else clears the budgets.
            raise ValueError("alpha_grid must contain 1.0, the baseline corner")
        # Validates epsilon and the temperatures through one shared definition.
        for alpha in self.alpha_grid:
            for temperature in self.temperature_grid:
                ProbabilityMixing(alpha, temperature, temperature, self.epsilon)

    def points(self) -> tuple[ProbabilityMixing, ...]:
        """Every *behaviourally distinct* mixing point, in a fixed order.

        A temperature on a half that carries no weight changes nothing, and the
        deduplication says which those are. ``alpha = 0`` drops the softmax
        temperature: the half is unused. ``alpha = 1`` drops both, because
        temperature-scaling a softmax cannot reorder its probabilities and the
        decoding is an argmax over them.

        The ordinal temperature is kept at ``alpha = 0``. It is *not* an
        order-preserving rescaling there: the class probabilities are adjacent
        differences of independently scaled sigmoids, and scaling them can and
        does change which difference is largest.
        """
        seen: dict[tuple[float, float, float], ProbabilityMixing] = {}
        for alpha in self.alpha_grid:
            for temperature_softmax in self.temperature_grid:
                for temperature_ordinal in self.temperature_grid:
                    softmax = temperature_softmax if 0.0 < alpha < 1.0 else 1.0
                    ordinal = 1.0 if alpha == 1.0 else temperature_ordinal
                    seen.setdefault(
                        (alpha, softmax, ordinal),
                        ProbabilityMixing(alpha, softmax, ordinal, self.epsilon),
                    )
        return tuple(seen.values())

    def reference(self) -> ProbabilityMixing:
        """The ``alpha = 1`` corner: the stage-1 softmax head, unmixed."""
        return ProbabilityMixing(1.0, 1.0, 1.0, self.epsilon)

    def to_dict(self) -> dict[str, object]:
        data = asdict(self)
        data["alpha_grid"] = list(self.alpha_grid)
        data["temperature_grid"] = list(self.temperature_grid)
        data["monotonicity"] = "cumulative_min"
        data["decoding"] = "argmax_mixed_class_probability"
        data["selection"] = (
            "max_validation_low_engagement_recall "
            "under false_alarm_budget_per_90min and accuracy_drop_budget"
        )
        return data


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
        # Dual head only, and deliberately unset until the Validation grid search
        # picks a point: a plain attribute rather than a buffer, because these are
        # exact decimals recorded in `summary.json` and compared against the
        # protocol's grid, and a float32 buffer cannot hold 1e-6 exactly.
        # `_save_checkpoint` writes it out and `load_checkpoint` restores it, so it
        # still travels with the weights it was chosen for.
        self._mixing: ProbabilityMixing | None = None
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
        if self.config.head in ("softmax", DUAL_HEAD):
            # Unchanged submodule structure/keys so existing E0/E0-A
            # checkpoints (state_dict keys `classifier.0.*`, `classifier.3.*`)
            # still load. The dual head keeps the same keys deliberately: its
            # softmax half *is* a stage-1 E0 head, loaded rather than trained.
            self.classifier = nn.Sequential(
                nn.Linear(self.config.d_model, self.config.mlp_dim),
                nn.ReLU(),
                nn.Dropout(self.config.dropout),
                nn.Linear(self.config.mlp_dim, self.config.num_classes),
            )
        if self.config.head in ("ordinal_binary", DUAL_HEAD):
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
        if self.config.head == "coral":
            self.classifier_shared = nn.Sequential(
                nn.Linear(self.config.d_model, self.config.mlp_dim),
                nn.ReLU(),
                nn.Dropout(self.config.dropout),
            )
            self.coral_fc = nn.Linear(self.config.mlp_dim, 1)
            self.coral_bias = nn.Parameter(torch.zeros(self.config.num_classes - 1))

    def backbone_modules(self) -> tuple[nn.Module, ...]:
        """The parts a two-stage protocol inherits from stage 1 and freezes.

        Normally the shared representation, i.e. everything before the head. The
        dual head adds its softmax half: that head is stage 1's, reused so the
        mixture's ``alpha = 1`` corner *is* the baseline's decision. Training it
        further would move the corner and there would be nothing to compare to.
        """
        shared: tuple[nn.Module, ...] = (self.input_projection, self.encoder)
        if self.config.head == DUAL_HEAD:
            return (*shared, self.classifier)
        return shared

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

    def pooled_representation(self, tokens: Tensor) -> Tensor:
        """The encoder output every head reads, produced once per input."""
        normalized = (tokens - self.feature_mean) / self.feature_std
        embedded = self.input_projection(normalized) + self.position_embedding
        return cast(Tensor, self.encoder(embedded)).amax(dim=1)

    def _ordinal_logits(self, pooled: Tensor) -> Tensor:
        return torch.cat([head(pooled) for head in self.ordinal_heads], dim=1)

    def dual_head_logits(self, tokens: Tensor) -> tuple[Tensor, Tensor]:
        """``(softmax logits [B,K], ordinal threshold logits [B,K-1])``.

        One encoder pass feeds both, which is the point of the dual head: the
        cost over the single-head model is two small MLPs on an already-computed
        pooled vector, not a second forward through the Transformer.
        """
        if self.config.head != DUAL_HEAD:
            raise ValueError(f"dual_head_logits requires the {DUAL_HEAD} head")
        pooled = self.pooled_representation(tokens)
        return cast(Tensor, self.classifier(pooled)), self._ordinal_logits(pooled)

    def forward(self, tokens: Tensor) -> Tensor:
        pooled = self.pooled_representation(tokens)
        if self.config.head == "softmax":
            return cast(Tensor, self.classifier(pooled))
        # The dual head trains and selects its checkpoint on the ordinal half --
        # the only half with gradients -- so its `forward` is the ordinal one and
        # the loss, the objective and the epoch metrics need no dual-head case.
        # The deployed output is `DualHeadMixture`, applied after training.
        if self.config.head in ("ordinal_binary", DUAL_HEAD):
            return self._ordinal_logits(pooled)
        feat = self.classifier_shared(pooled)
        return cast(Tensor, self.coral_fc(feat) + self.coral_bias)

    def probability_mixing(self) -> ProbabilityMixing:
        """The selected mixing point, or a refusal if none was ever selected.

        Refusing rather than defaulting: a dual-head model whose ``alpha`` was
        never chosen has no defined output, and quietly substituting one would
        ship a model nobody selected.
        """
        if self._mixing is None:
            raise ValueError(
                f"this {DUAL_HEAD} model has no selected mixing point; "
                "run select_probability_mixing on Validation first"
            )
        return self._mixing

    def has_probability_mixing(self) -> bool:
        return self._mixing is not None

    def set_probability_mixing(self, mixing: ProbabilityMixing) -> None:
        if self.config.head != DUAL_HEAD:
            raise ValueError(f"probability mixing requires the {DUAL_HEAD} head")
        self._mixing = mixing


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


def mixed_log_probabilities(
    softmax_logits: Tensor, ordinal_logits: Tensor, mixing: ProbabilityMixing
) -> Tensor:
    """``log(p_safe)`` for the dual head: the tensor the browser consumes.

    Both halves are turned into 4-class probabilities over the *same* class order
    before anything is combined -- a softmax over temperature-scaled logits, and
    the repaired adjacent differences of the temperature-scaled thresholds -- so
    ``p_mix`` is a probability vector and ``alpha`` is a probability weight
    rather than a logit blend, which would depend on each half's scale.

    The result is a logarithm because the deployed contract is
    ``softmax(output) == p_safe``: the browser already softmaxes what it reads as
    logits, and softmax undoes a log exactly. That is what lets the mixture ship
    without changing one line of frontend code.

    ``epsilon`` is clamped in and the vector renormalized, so a half that
    saturates to an exact zero -- which a sigmoid at a large logit does -- cannot
    become ``log(0)``. The clamp costs at most ``epsilon`` of probability mass per
    class, four orders of magnitude below anything the 0.35 decision reads.
    """
    probability_softmax = (softmax_logits / mixing.temperature_softmax).softmax(dim=1)
    probability_ordinal = ordinal_binary_class_probabilities(
        ordinal_logits / mixing.temperature_ordinal
    )
    mixed = mixing.alpha * probability_softmax + (1 - mixing.alpha) * probability_ordinal
    safe = mixed.clamp_min(mixing.epsilon)
    return (safe / safe.sum(dim=1, keepdim=True)).log()


class DualHeadMixture(nn.Module):
    """A dual-head model seen the way everything downstream sees it.

    Its output is ``log(p_safe)``, and it reports ``head="softmax"`` so that
    :func:`training.make_objective` decodes it with the plain argmax-and-softmax
    rule -- which is precisely what the browser does. Test evaluation, the
    Validation grid search and the ONNX export therefore all measure the
    deployed decision rather than one head of it.

    The mixing point is read from the wrapped model's buffers on every call, so
    a wrapper built before selection reflects the selection afterwards.
    """

    def __init__(self, model: EngagementTransformer) -> None:
        super().__init__()
        if model.config.head != DUAL_HEAD:
            raise ValueError(f"DualHeadMixture requires the {DUAL_HEAD} head")
        # Raises when nothing was selected, which is the point: Test evaluation
        # and ONNX export both go through here, so neither can run against a
        # mixture that was never chosen.
        self.mixing = model.probability_mixing()
        self.model = model
        self.config = replace(model.config, head="softmax")

    def forward(self, tokens: Tensor) -> Tensor:
        softmax_logits, ordinal_logits = self.model.dual_head_logits(tokens)
        return mixed_log_probabilities(softmax_logits, ordinal_logits, self.mixing)


def deployment_view(model: nn.Module) -> nn.Module:
    """The module whose output is what ships, for any model family.

    Only the dual head differs from its own ``forward``; everything else is
    returned untouched, so callers do not have to know which heads exist.
    """
    if isinstance(model, EngagementTransformer) and model.config.head == DUAL_HEAD:
        return DualHeadMixture(model).eval()
    return model


__all__ = [
    "DUAL_HEAD",
    "HEADS",
    "DualHeadMixture",
    "EngagementTransformer",
    "MixingProtocol",
    "ModelConfig",
    "ProbabilityMixing",
    "deployment_view",
    "mixed_log_probabilities",
    "monotone_cumulative_probabilities",
    "ordinal_binary_class_probabilities",
]
