from __future__ import annotations

import hashlib
import json
import math
import random
from collections.abc import Callable, Iterable, Iterator, Mapping
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Protocol, cast

import numpy as np
import torch
from numpy.typing import NDArray
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    cohen_kappa_score,
    confusion_matrix,
    f1_score,
)
from torch import Tensor, nn
from torch.utils.data import DataLoader, Dataset, Subset, WeightedRandomSampler

from zani_ai.engagement.contracts import (
    CLASS_WEIGHTING_SCHEMES,
    LABELS,
    LOSS_SCHEMES,
    SAMPLER_SCHEMES,
    TARGET_ENCODINGS,
    SplitName,
    low_engagement_metrics,
)
from zani_ai.engagement.features import SCHEMA_NAME, TOKEN_FEATURE_COUNT, get_schema
from zani_ai.engagement.model import (
    DUAL_HEAD,
    EngagementTransformer,
    MixingProtocol,
    ModelConfig,
    ProbabilityMixing,
    deployment_view,
    mixed_log_probabilities,
    ordinal_binary_class_probabilities,
)


@dataclass(frozen=True, slots=True)
class FeatureStatistics:
    mean: NDArray[np.float32]
    std: NDArray[np.float32]


@dataclass(frozen=True, slots=True)
class FeatureEntry:
    clip_id: str
    split: SplitName
    label_index: int
    feature_path: Path


class CachedFeatureDataset(Dataset[tuple[Tensor, Tensor]]):
    def __init__(
        self,
        entries: tuple[FeatureEntry, ...],
        *,
        token_feature_count: int = TOKEN_FEATURE_COUNT,
        array_key: str = "tokens",
        array_shape: tuple[int, ...] | None = None,
    ) -> None:
        if not entries:
            raise ValueError("feature split is empty")
        self.entries = entries
        self.token_feature_count = token_feature_count
        self.array_key = array_key
        # Default preserves the existing (20, token_feature_count) token-path check.
        self.array_shape = array_shape if array_shape is not None else (20, token_feature_count)
        # Lazy in-memory cache: each sample's feature tensor is loaded from disk once and
        # reused across epochs, so training is not bottlenecked on per-epoch npz I/O (keeps the
        # GPU fed). Returns the identical tensor data, so results/determinism are unchanged.
        self._feature_cache: list[Tensor | None] = [None] * len(entries)

    def __len__(self) -> int:
        return len(self.entries)

    def __getitem__(self, index: int) -> tuple[Tensor, Tensor]:
        entry = self.entries[index]
        cached = self._feature_cache[index]
        if cached is None:
            with np.load(entry.feature_path, allow_pickle=False) as cache:
                array = np.asarray(cache[self.array_key], dtype=np.float32)
            if array.shape != self.array_shape or not np.isfinite(array).all():
                raise ValueError(f"invalid cached {self.array_key}: {entry.feature_path}")
            cached = torch.from_numpy(array)
            self._feature_cache[index] = cached
        return cached, torch.tensor(entry.label_index, dtype=torch.long)

    def token_arrays(self) -> Iterator[NDArray[np.float32]]:
        for entry in self.entries:
            with np.load(entry.feature_path, allow_pickle=False) as cache:
                yield np.asarray(cache[self.array_key], dtype=np.float32)


@dataclass(frozen=True, slots=True)
class FeatureDatasets:
    train: CachedFeatureDataset
    valid: CachedFeatureDataset
    test: CachedFeatureDataset | None


@dataclass(frozen=True, slots=True)
class TrainingConfig:
    features_root: Path
    output_dir: Path
    max_epochs: int = 200
    batch_size: int = 32
    learning_rate: float = 1e-4
    patience: int = 20
    seed: int = 42
    device: str = "cuda" if torch.cuda.is_available() else "cpu"
    # "none" | "balanced" | "sqrt_balanced"; see CLASS_WEIGHTING_SCHEMES.
    class_weighting: str = "none"
    # "cross_entropy" | "focal"; see LOSS_SCHEMES. Ignored by the CORAL head,
    # which brings its own objective.
    loss: str = "cross_entropy"
    focal_gamma: float = 2.0
    # "none" | "balanced"; see SAMPLER_SCHEMES. Applies to the training split only.
    sampler: str = "none"
    # "one_hot" | "sord"; see TARGET_ENCODINGS. Softmax head only -- the CORAL
    # head has no class-probability target to soften.
    target_encoding: str = "one_hot"
    sord_alpha: float = 2.0
    num_workers: int = 0
    deterministic: bool = False
    model: ModelConfig = field(default_factory=ModelConfig)
    # E1 (non-Transformer) hooks. Defaults reproduce the Transformer path exactly.
    build_model: Callable[..., nn.Module] | None = None
    needs_feature_stats: bool = True
    lr_step: int | None = None
    array_key: str = "tokens"
    array_shape: tuple[int, ...] | None = None
    # E0-L / E0-M only: the stage-1 checkpoint whose backbone stage 2 freezes.
    # Required by, and restricted to, the two-stage ordinal heads.
    stage1_checkpoint: Path | None = None
    # E0-M only: the grid the deployed mixing point is chosen from on Validation.
    # Required by, and restricted to, the dual head.
    mixing: MixingProtocol | None = None
    # E0-I only. Defaults preserve every existing protocol and checkpoint hash.
    curriculum: str = "none"
    reliability_manifest: Path | None = None
    reliable_warmup_epochs: int = 10
    ambiguous_target_encoding: str = "adjacent_smoothing"
    ambiguous_neighbor_mass: float = 0.2


@dataclass(frozen=True, slots=True)
class EvaluationMetrics:
    accuracy: float
    macro_f1: float
    within_one_accuracy: float
    quadratic_weighted_kappa: float
    confusion_matrix: list[list[int]]
    classification_report: dict[str, object]
    #: Share of adjacent threshold pairs whose independent binary heads came out
    #: in the wrong order, measured *before* the monotonicity repair. ``None``
    #: for every head that has no such pairs, which keeps "not applicable"
    #: distinguishable from "never violated".
    monotonicity_violation_rate: float | None = None

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class TrainingResult:
    checkpoint_path: Path
    metrics_path: Path
    best_epoch: int
    validation: EvaluationMetrics
    test: EvaluationMetrics | None
    #: Dual head only: the Validation-selected mixing point and its grid.
    mixing: MixingSelection | None = None


#: ``(epoch, validation, stale_epochs)``. ``stale_epochs`` is how many epochs in
#: a row had already failed to improve *before* this one, so
#: ``patience - stale_epochs`` is the fewest epochs early stopping can still
#: require. It is there so a progress line can put a floor under "when does this
#: finish", which a countdown to ``max_epochs`` cannot: this family's measured
#: ``best_epoch`` is 2~11 against a 200-epoch budget, so runs end near epoch 30
#: and a naive ETA overstates by most of an order of magnitude.
type EpochProgress = Callable[[int, EvaluationMetrics, int], None]


def validate_manifest_completion(
    payload: Mapping[str, Any],
) -> tuple[list[Any], list[Any]]:
    included = payload.get("included")
    excluded = payload.get("excluded")
    if not isinstance(included, list) or not isinstance(excluded, list):
        raise ValueError("feature manifest must contain included and excluded lists")

    has_status = "status" in payload
    has_complete = "complete" in payload
    if not has_status and not has_complete:
        return included, excluded
    if payload.get("status") != "complete" or payload.get("complete") is not True:
        raise ValueError(
            f"feature manifest is incomplete (status={payload.get('status')!r}); "
            "finish extraction first"
        )

    counts: dict[str, int] = {}
    for name in ("processed_count", "total_count"):
        value = payload.get(name)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise ValueError(f"feature manifest {name} must be a non-negative integer")
        counts[name] = value
    listed_count = len(included) + len(excluded)
    if counts["processed_count"] != listed_count:
        raise ValueError("feature manifest processed_count does not match its clip lists")
    if counts["processed_count"] != counts["total_count"]:
        raise ValueError("feature manifest is incomplete (processed_count != total_count)")
    cached_count = payload.get("cached_count")
    if cached_count is not None and (
        not isinstance(cached_count, int)
        or isinstance(cached_count, bool)
        or not 0 <= cached_count <= len(included)
    ):
        raise ValueError("feature manifest cached_count is inconsistent")
    scanned_count = payload.get("scanned_count")
    if scanned_count is not None and (
        not isinstance(scanned_count, int)
        or isinstance(scanned_count, bool)
        or scanned_count != counts["total_count"]
    ):
        raise ValueError("feature manifest scanned_count is inconsistent")
    excluded_fraction = payload.get("excluded_fraction")
    expected_fraction = len(excluded) / counts["total_count"] if counts["total_count"] else 0.0
    if excluded_fraction is not None and (
        not isinstance(excluded_fraction, int | float)
        or isinstance(excluded_fraction, bool)
        or not math.isfinite(excluded_fraction)
        or not math.isclose(excluded_fraction, expected_fraction, rel_tol=0, abs_tol=1e-12)
    ):
        raise ValueError("feature manifest excluded_fraction does not match its clip lists")

    identities: set[tuple[str, str]] = set()
    for kind, items in (("included", included), ("excluded", excluded)):
        for index, item in enumerate(items):
            if not isinstance(item, dict):
                raise ValueError(f"invalid {kind} entry at index {index}")
            split = item.get("split")
            clip_id = item.get("clip_id")
            if not isinstance(split, str) or not isinstance(clip_id, str) or not clip_id:
                raise ValueError(f"invalid {kind} identity at index {index}")
            identity = (split, clip_id)
            if identity in identities:
                raise ValueError(f"duplicate feature manifest clip identity: {split}/{clip_id}")
            identities.add(identity)
    return included, excluded


def compute_feature_statistics(
    arrays: Iterable[NDArray[np.float32]],
    *,
    token_feature_count: int = TOKEN_FEATURE_COUNT,
) -> FeatureStatistics:
    materialized = [
        np.asarray(array, dtype=np.float64).reshape(-1, token_feature_count) for array in arrays
    ]
    if not materialized:
        raise ValueError("cannot compute statistics from an empty training split")
    values = np.concatenate(materialized, axis=0)
    if not np.isfinite(values).all():
        raise ValueError("training features contain non-finite values")
    return FeatureStatistics(
        values.mean(axis=0).astype(np.float32), values.std(axis=0).astype(np.float32)
    )


def _load_feature_datasets(
    root: Path,
    *,
    include_test: bool = True,
    expected_schema: str = SCHEMA_NAME,
    array_key: str = "tokens",
    array_shape: tuple[int, ...] | None = None,
) -> FeatureDatasets:
    manifest_path = root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"feature manifest not found: {manifest_path}")
    payload = cast(dict[str, Any], json.loads(manifest_path.read_text(encoding="utf-8")))
    manifest_schema = payload.get("schema")
    if manifest_schema != expected_schema:
        raise ValueError(
            f"feature manifest schema must be {expected_schema!r}, got {manifest_schema!r}"
        )
    # `get_schema` only knows token FeatureSchemas; when an explicit
    # `array_shape` is supplied (e.g. E1/ST-GCN's landmark schemas), it fully
    # determines the cached-array shape and `token_feature_count` is unused,
    # so skip the FeatureSchema lookup entirely for that path.
    token_feature_count = (
        get_schema(expected_schema).token_feature_count
        if array_shape is None
        else TOKEN_FEATURE_COUNT
    )
    included, _ = validate_manifest_completion(payload)
    grouped: dict[str, list[FeatureEntry]] = {"train": [], "valid": [], "test": []}
    for item_value in cast(list[dict[str, Any]], included):
        split = cast(SplitName, item_value["split"])
        if split not in grouped:
            raise ValueError(f"unknown feature split: {split}")
        path = root / str(item_value["feature_path"])
        if not path.is_file():
            raise FileNotFoundError(f"cached feature not found: {path}")
        if split == "test" and not include_test:
            continue
        grouped[split].append(
            FeatureEntry(
                clip_id=str(item_value["clip_id"]),
                split=split,
                label_index=int(item_value["label_index"]),
                feature_path=path,
            )
        )
    return FeatureDatasets(
        CachedFeatureDataset(
            tuple(grouped["train"]),
            token_feature_count=token_feature_count,
            array_key=array_key,
            array_shape=array_shape,
        ),
        CachedFeatureDataset(
            tuple(grouped["valid"]),
            token_feature_count=token_feature_count,
            array_key=array_key,
            array_shape=array_shape,
        ),
        CachedFeatureDataset(
            tuple(grouped["test"]),
            token_feature_count=token_feature_count,
            array_key=array_key,
            array_shape=array_shape,
        )
        if include_test
        else None,
    )


def _seed_everything(seed: int, *, deterministic: bool) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)
    if deterministic:
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True
        torch.use_deterministic_algorithms(True, warn_only=False)
        if not torch.are_deterministic_algorithms_enabled():
            raise RuntimeError("PyTorch deterministic algorithms could not be enabled")


def _balanced_sample_weights(dataset: CachedFeatureDataset) -> Tensor:
    """Per-*sample* draw weights that give every class the same total mass.

    Unlike :func:`_class_weights`, which scales the loss, these decide how often
    a sample is drawn. ``w = 1 / count[label]`` makes each class sum to 1, so a
    ``len(dataset)``-draw epoch is class-uniform in expectation.
    """
    counts = np.bincount([entry.label_index for entry in dataset.entries], minlength=len(LABELS))
    if np.any(counts == 0):
        raise ValueError("balanced sampling requires every class in the training split")
    weights = 1.0 / counts[[entry.label_index for entry in dataset.entries]]
    return torch.as_tensor(weights, dtype=torch.double)


def _loader(
    dataset: CachedFeatureDataset,
    config: TrainingConfig,
    *,
    shuffle: bool,
) -> DataLoader[tuple[Tensor, Tensor]]:
    generator = torch.Generator().manual_seed(config.seed)
    if config.sampler not in SAMPLER_SCHEMES:
        raise ValueError(f"sampler must be one of {SAMPLER_SCHEMES}, got {config.sampler!r}")
    # Only the training loader is resampled. Reweighting the evaluation splits
    # would change the distribution Macro F1 is measured on.
    sampler = (
        WeightedRandomSampler(
            # `tolist()` because the sampler is typed for `Sequence[float]`; it
            # converts straight back to a double tensor, so values are unchanged.
            _balanced_sample_weights(dataset).tolist(),
            num_samples=len(dataset),
            replacement=True,
            # Drawing with replacement is a random process; without the seeded
            # generator the protocol would not reproduce across runs.
            generator=generator,
        )
        if shuffle and config.sampler == "balanced"
        else None
    )
    return DataLoader(
        dataset,
        batch_size=config.batch_size,
        shuffle=shuffle if sampler is None else False,
        sampler=sampler,
        num_workers=config.num_workers,
        generator=generator,
        pin_memory=torch.cuda.is_available(),
    )


def _class_weights(
    dataset: CachedFeatureDataset, device: torch.device, scheme: str = "balanced"
) -> Tensor:
    """Per-class loss weights for ``scheme``, normalized to a mean of 1.

    Both schemes satisfy ``sum(count_i * weight_i) == len(dataset)``, so the
    loss keeps the same scale as unweighted training and the learning rate
    stays comparable across protocols.
    """
    if scheme not in CLASS_WEIGHTING_SCHEMES:
        raise ValueError(
            f"class_weighting must be one of {CLASS_WEIGHTING_SCHEMES}, got {scheme!r}"
        )
    counts = np.bincount([entry.label_index for entry in dataset.entries], minlength=len(LABELS))
    if np.any(counts == 0):
        raise ValueError("class weighting requires every class in the training split")
    if scheme == "balanced":
        weights = len(dataset) / (len(LABELS) * counts)
    else:  # sqrt_balanced
        roots = np.sqrt(counts)
        weights = len(dataset) / (roots * roots.sum())
    return torch.as_tensor(weights, dtype=torch.float32, device=device)


class Objective(Protocol):
    """Training objective: loss + label/probability decoding for a model head."""

    def loss(self, out: Tensor, labels: Tensor) -> Tensor: ...

    def predict(self, out: Tensor) -> Tensor: ...

    def class_probs(self, out: Tensor) -> Tensor: ...


class SoftmaxObjective:
    """Standard 4-way classification objective (E0/E0-A behavior)."""

    def __init__(self, weight: Tensor | None = None) -> None:
        self.criterion = nn.CrossEntropyLoss(weight=weight)

    def loss(self, out: Tensor, labels: Tensor) -> Tensor:
        return self.criterion(out, labels)

    def predict(self, out: Tensor) -> Tensor:
        return out.argmax(dim=1)

    def class_probs(self, out: Tensor) -> Tensor:
        return out.softmax(dim=1)


class AdjacentSmoothingObjective:
    """One-hot targets for reliable clips and symmetric adjacent smoothing otherwise."""

    def __init__(self, num_classes: int = 4, neighbor_mass: float = 0.2) -> None:
        if num_classes < 2:
            raise ValueError("adjacent smoothing requires at least two classes")
        if not 0 < neighbor_mass < 1:
            raise ValueError("ambiguous_neighbor_mass must be in (0, 1)")
        self.num_classes = num_classes
        self.neighbor_mass = neighbor_mass

    def soft_targets(self, labels: Tensor, ambiguous: Tensor) -> Tensor:
        targets = nn.functional.one_hot(labels, num_classes=self.num_classes).float()
        ambiguous_indices = ambiguous.nonzero(as_tuple=False).flatten()
        for index in ambiguous_indices.tolist():
            label = int(labels[index])
            targets[index].zero_()
            targets[index, label] = 1.0 - self.neighbor_mass
            neighbours = [
                candidate
                for candidate in (label - 1, label + 1)
                if 0 <= candidate < self.num_classes
            ]
            neighbour_probability = self.neighbor_mass / len(neighbours)
            targets[index, neighbours] = neighbour_probability
        return targets

    def loss(self, out: Tensor, labels: Tensor, ambiguous: Tensor) -> Tensor:
        return nn.functional.cross_entropy(out, self.soft_targets(labels, ambiguous))


class ReliabilityFeatureDataset(Dataset[tuple[Tensor, Tensor, Tensor]]):
    def __init__(self, base: CachedFeatureDataset, ambiguous: tuple[bool, ...]) -> None:
        if len(base) != len(ambiguous):
            raise ValueError("reliability labels must match the training feature count")
        self.base = base
        self.ambiguous = ambiguous

    def __len__(self) -> int:
        return len(self.base)

    def __getitem__(self, index: int) -> tuple[Tensor, Tensor, Tensor]:
        tokens, label = self.base[index]
        return tokens, label, torch.tensor(self.ambiguous[index], dtype=torch.bool)


class FocalObjective:
    """Focal loss (Lin et al., 2017) over the same 4-way softmax head as E0.

    ``FL = -alpha_t (1 - p_t)^gamma log(p_t)``. Where ``class_weighting`` scales
    by class frequency, the ``(1 - p_t)^gamma`` term scales by how confidently
    the sample is already classified, so the majority class stops dominating the
    gradient once it is easy. The two are orthogonal and ``alpha`` composes with
    either scheme.

    Reduction matches ``nn.CrossEntropyLoss(weight=...)`` -- a weighted mean,
    not a plain one -- so a weighted run keeps the loss scale of an unweighted
    one and the learning rate carries over.
    """

    def __init__(self, gamma: float = 2.0, alpha: Tensor | None = None) -> None:
        if gamma < 0:
            raise ValueError(f"focal_gamma must be non-negative, got {gamma!r}")
        self.gamma = gamma
        self.alpha = alpha

    def loss(self, out: Tensor, labels: Tensor) -> Tensor:
        cross_entropy = nn.functional.cross_entropy(out, labels, reduction="none")
        # p_t = exp(-CE) is the probability assigned to the true class.
        modulation = (1 - torch.exp(-cross_entropy)) ** self.gamma
        focal = modulation * cross_entropy
        if self.alpha is None:
            return focal.mean()
        weights = self.alpha[labels]
        return (weights * focal).sum() / weights.sum()

    def predict(self, out: Tensor) -> Tensor:
        return out.argmax(dim=1)

    def class_probs(self, out: Tensor) -> Tensor:
        return out.softmax(dim=1)


class SordObjective:
    """SORD soft ordinal targets (Diaz & Marathe, CVPR 2019) on E0's softmax head.

    ``target_j = softmax(-alpha * (i - j)^2)_j`` for true grade ``i``, which is
    exactly the paper's ``exp(-phi(y_i, y_j))`` normalized over the grades with
    ``phi`` the squared grade distance. Computing it as a softmax over the
    negated penalties rather than an explicit ``exp`` and divide is the same
    value without the overflow risk.

    Unlike CORAL, which replaces the head with cumulative threshold logits and
    failed here (E0-B, macro-F1 0.5185), nothing but the *target* moves: the
    4-way head, the argmax decoding and every metric stay exactly as E0 has
    them, so a difference is attributable to the target encoding alone.

    ``alpha`` controls how much probability leaks to the neighbours -- large
    values converge on one-hot, small ones on uniform -- so it is part of the
    protocol identity rather than a tuning knob.

    No class weighting: ``CrossEntropyLoss(weight=)`` scales each sample by the
    weight of its *hard* label, which no longer means "per-class loss mass" once
    the target is spread across grades. :func:`make_objective` rejects the
    combination instead of silently reinterpreting it.
    """

    def __init__(self, num_classes: int = 4, alpha: float = 2.0) -> None:
        if alpha <= 0:
            raise ValueError(f"sord_alpha must be positive, got {alpha!r}")
        self.num_classes = num_classes
        self.alpha = alpha

    def soft_targets(self, labels: Tensor) -> Tensor:
        grades = torch.arange(self.num_classes, device=labels.device, dtype=torch.float32)
        distance = labels.unsqueeze(1).to(grades.dtype) - grades.unsqueeze(0)  # [B,C]
        return (-self.alpha * distance.square()).softmax(dim=1)

    def loss(self, out: Tensor, labels: Tensor) -> Tensor:
        # `cross_entropy` accepts probability targets, giving the plain
        # -sum(target * log_softmax(out)) mean over the batch.
        return nn.functional.cross_entropy(out, self.soft_targets(labels))

    def predict(self, out: Tensor) -> Tensor:
        return out.argmax(dim=1)

    def class_probs(self, out: Tensor) -> Tensor:
        return out.softmax(dim=1)


class CoralObjective:
    """CORAL ordinal objective: k=num_classes-1 cumulative threshold logits."""

    def __init__(self, num_classes: int = 4) -> None:
        self.k = num_classes - 1

    def _targets(self, labels: Tensor) -> Tensor:
        j = torch.arange(self.k, device=labels.device).unsqueeze(0)
        return (labels.unsqueeze(1) > j).float()  # [B,k], 1[y>j]

    def loss(self, out: Tensor, labels: Tensor) -> Tensor:
        return nn.functional.binary_cross_entropy_with_logits(out, self._targets(labels))

    def predict(self, out: Tensor) -> Tensor:
        return (out > 0).sum(dim=1).long()  # count sigmoid(z)>0.5

    def class_probs(self, out: Tensor) -> Tensor:
        pg = torch.sigmoid(out)  # P(y>k) [B,k]
        first = 1 - pg[:, :1]
        mid = pg[:, :-1] - pg[:, 1:]
        last = pg[:, -1:]
        p = torch.cat([first, mid, last], dim=1).clamp_min(0)
        return p / p.sum(dim=1, keepdim=True)


class OrdinalBinaryObjective:
    """K-1 *independent* binary heads over the grade order (ticket 237).

    The targets are CORAL's cumulative indicators ``1[y>j]``, but nothing is
    shared between the heads, so the sigmoid outputs are not ordered and
    ``P(y>0) >= P(y>1) >= P(y>2)`` can be violated. The rule for that case is
    fixed here rather than left to the caller: the running minimum projects the
    cumulative probabilities onto the monotone cone, and the class distribution
    is read off the adjacent differences (see
    :func:`model.monotone_cumulative_probabilities`).

    Decoding is the ``argmax`` of those class probabilities, not CORAL's
    ``count(p > 0.5)``. The exported ONNX graph emits this probability vector and
    the browser argmaxes it (after a softmax, which preserves order), so the two
    rules disagreeing -- ``p̃ = (0.9, 0.6, 0.4)`` is rank 2 but argmax 3 -- would
    mean the measured number is not the deployed behavior.
    """

    def __init__(self, num_classes: int = 4) -> None:
        self.k = num_classes - 1

    def _targets(self, labels: Tensor) -> Tensor:
        j = torch.arange(self.k, device=labels.device).unsqueeze(0)
        return (labels.unsqueeze(1) > j).float()  # [B,k], 1[y>j]

    def loss(self, out: Tensor, labels: Tensor) -> Tensor:
        return nn.functional.binary_cross_entropy_with_logits(out, self._targets(labels))

    def predict(self, out: Tensor) -> Tensor:
        return self.class_probs(out).argmax(dim=1)

    def class_probs(self, out: Tensor) -> Tensor:
        return ordinal_binary_class_probabilities(out)

    def violation_counts(self, out: Tensor) -> tuple[int, int]:
        """``(violated adjacent pairs, total adjacent pairs)`` before repair.

        Reported so the repair cannot hide how often it was needed: a protocol
        whose heads disagree with the grade order on most samples is a different
        finding from one whose repair never fires.
        """
        probabilities = torch.sigmoid(out)
        violated = probabilities[:, :-1] < probabilities[:, 1:]
        return int(violated.sum().item()), int(violated.numel())


def make_objective(
    config: Any,
    class_weights: Tensor | None = None,
    *,
    loss: str = "cross_entropy",
    focal_gamma: float = 2.0,
    target_encoding: str = "one_hot",
    sord_alpha: float = 2.0,
) -> Objective:
    if target_encoding not in TARGET_ENCODINGS:
        raise ValueError(
            f"target_encoding must be one of {TARGET_ENCODINGS}, got {target_encoding!r}"
        )
    # `config` is a ModelConfig (Transformer) or STGCNConfig (ST-GCN, no `head`
    # attribute); any config without a `head` defaults to softmax.
    head = getattr(config, "head", "softmax")
    if head in ("coral", "ordinal_binary", DUAL_HEAD):
        if target_encoding != "one_hot":
            # Both heads fit cumulative 1[y>j] indicators, so there is no class
            # distribution left for SORD to soften. Ignoring the request would
            # let a run record a target encoding it never trained with.
            name = "CORAL" if head == "coral" else "ordinal-binary"
            raise ValueError(f"target_encoding is not applicable to the {name} head")
        # These heads replace the softmax head itself, so `loss` does not apply.
        # The dual head trains only its ordinal half, so it shares that
        # objective; `DualHeadMixture` reports `head="softmax"` and lands below,
        # which is how the deployed mixture gets the plain argmax decoding.
        if head in ("ordinal_binary", DUAL_HEAD):
            return OrdinalBinaryObjective(config.num_classes)
        return CoralObjective(config.num_classes)
    if loss not in LOSS_SCHEMES:
        raise ValueError(f"loss must be one of {LOSS_SCHEMES}, got {loss!r}")
    if target_encoding == "sord":
        if loss != "cross_entropy":
            raise ValueError("sord targets require loss='cross_entropy'")
        if class_weights is not None:
            # See SordObjective: per-class weights are defined against a hard
            # label, which a spread target no longer has.
            raise ValueError("sord targets cannot be combined with class weighting")
        return SordObjective(config.num_classes, alpha=sord_alpha)
    if loss == "focal":
        return FocalObjective(gamma=focal_gamma, alpha=class_weights)
    return SoftmaxObjective(weight=class_weights)


def _train_epoch(
    model: nn.Module,
    loader: DataLoader[tuple[Tensor, Tensor]],
    optimizer: torch.optim.Optimizer,
    objective: Objective,
    device: torch.device,
) -> None:
    model.train()
    for tokens, labels in loader:
        optimizer.zero_grad(set_to_none=True)
        loss = objective.loss(model(tokens.to(device)), labels.to(device))
        loss.backward()
        optimizer.step()


def _train_curriculum_epoch(
    model: nn.Module,
    loader: DataLoader[tuple[Tensor, Tensor, Tensor]],
    optimizer: torch.optim.Optimizer,
    objective: AdjacentSmoothingObjective,
    device: torch.device,
) -> None:
    model.train()
    for tokens, labels, ambiguous in loader:
        optimizer.zero_grad(set_to_none=True)
        loss = objective.loss(model(tokens.to(device)), labels.to(device), ambiguous.to(device))
        loss.backward()  # type: ignore[no-untyped-call]
        optimizer.step()


def _manifest_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load_curriculum_ambiguity(
    config: TrainingConfig,
    manifest_path: Path,
    datasets: FeatureDatasets,
) -> tuple[bool, ...] | None:
    if config.curriculum == "none":
        if config.reliability_manifest is not None:
            raise ValueError("reliability_manifest requires a curriculum")
        return None
    if config.curriculum != "label_reliability_v1":
        raise ValueError(f"unknown curriculum: {config.curriculum!r}")
    if config.reliability_manifest is None:
        raise ValueError("label_reliability_v1 requires reliability_manifest")
    if config.reliable_warmup_epochs <= 0:
        raise ValueError("reliable_warmup_epochs must be positive")
    if config.ambiguous_target_encoding != "adjacent_smoothing":
        raise ValueError("label_reliability_v1 requires adjacent_smoothing targets")
    if (
        config.class_weighting != "none"
        or config.loss != "cross_entropy"
        or config.sampler != "none"
        or config.target_encoding != "one_hot"
        or config.lr_step is not None
    ):
        raise ValueError("label_reliability_v1 requires the unweighted E0 objective and schedule")
    # Lazy import avoids a module cycle: reliability inference uses this module's
    # checkpoint and feature-dataset readers.
    from zani_ai.engagement.reliability import load_validated_reliability_manifest

    validated = load_validated_reliability_manifest(
        config.reliability_manifest,
        feature_manifest_sha256=_manifest_sha256(manifest_path),
        require_go=True,
    )
    records: dict[tuple[str, str], tuple[int, str]] = {}
    for item in validated.records:
        identity = (item.split, item.clip_id)
        if identity in records:
            raise ValueError(f"duplicate reliability clip: {item.split}/{item.clip_id}")
        records[identity] = (item.label, item.reliability)

    expected = {
        (entry.split, entry.clip_id): entry.label_index
        for dataset in (datasets.train, datasets.valid)
        for entry in dataset.entries
    }
    if set(records) != set(expected):
        raise ValueError(
            "reliability manifest clip coverage differs from Train/Validation features"
        )
    for identity, expected_label in expected.items():
        if records[identity][0] != expected_label:
            raise ValueError(f"reliability label differs for {identity[0]}/{identity[1]}")

    ambiguity = tuple(
        records[(entry.split, entry.clip_id)][1] == "ambiguous" for entry in datasets.train.entries
    )
    reliable_labels = {
        entry.label_index
        for entry, ambiguous in zip(datasets.train.entries, ambiguity, strict=True)
        if not ambiguous
    }
    if reliable_labels != set(range(len(LABELS))):
        raise ValueError("reliable Train clips must cover all labels")
    return ambiguity


def _curriculum_loader(
    dataset: Dataset[Any], config: TrainingConfig, *, shuffle: bool
) -> DataLoader[Any]:
    return DataLoader(
        dataset,
        batch_size=config.batch_size,
        shuffle=shuffle,
        num_workers=config.num_workers,
        generator=torch.Generator().manual_seed(config.seed),
        pin_memory=torch.cuda.is_available(),
    )


def ordinal_quality(expected: list[int], predicted: list[int]) -> tuple[float, float]:
    """(within-one accuracy, quadratic weighted kappa) -- ordinal-grade quality.

    An exact-match metric treats a one-grade miss the same as a three-grade
    one, but EngageNet's grades are ordered and its labels are subjective
    (annotators agree exactly only ~46% of the time). within-1 forgives
    adjacent-grade confusion; QWK penalizes by squared grade distance while
    correcting for chance agreement.
    """
    differences = np.abs(np.asarray(expected) - np.asarray(predicted))
    within_one = float(np.mean(differences <= 1))
    kappa = float(
        cohen_kappa_score(expected, predicted, labels=list(range(len(LABELS))), weights="quadratic")
    )
    # A degenerate input (both sides a single class) leaves the chance-correction
    # denominator at zero, which sklearn reports as NaN.
    return within_one, 0.0 if math.isnan(kappa) else kappa


def metrics_from_predictions(
    expected: list[int],
    predicted: list[int],
    *,
    monotonicity_violation_rate: float | None = None,
) -> EvaluationMetrics:
    """Every recorded metric, from decoded labels alone.

    Split out of :func:`evaluate_model` so the dual head's Validation grid search
    can score dozens of candidate mixing points from one cached forward pass and
    still report the same numbers a full evaluation would.
    """
    report = classification_report(
        expected,
        predicted,
        labels=list(range(len(LABELS))),
        target_names=list(LABELS),
        output_dict=True,
        zero_division=0,
    )
    within_one, kappa = ordinal_quality(expected, predicted)
    return EvaluationMetrics(
        accuracy=float(accuracy_score(expected, predicted)),
        macro_f1=float(f1_score(expected, predicted, labels=list(range(4)), average="macro")),
        within_one_accuracy=within_one,
        quadratic_weighted_kappa=kappa,
        confusion_matrix=confusion_matrix(expected, predicted, labels=list(range(4))).tolist(),
        classification_report=cast(dict[str, object], report),
        monotonicity_violation_rate=monotonicity_violation_rate,
    )


def evaluate_model(
    model: nn.Module,
    loader: DataLoader[tuple[Tensor, Tensor]],
    device: torch.device,
) -> EvaluationMetrics:
    model.eval()
    objective = make_objective(model.config)  # type: ignore[attr-defined]
    expected: list[int] = []
    predicted: list[int] = []
    violated_pairs = 0
    total_pairs = 0
    with torch.inference_mode():
        for tokens, labels in loader:
            logits = model(tokens.to(device))
            expected.extend(labels.tolist())
            predicted.extend(objective.predict(logits).cpu().tolist())
            if isinstance(objective, OrdinalBinaryObjective):
                violated, total = objective.violation_counts(logits)
                violated_pairs += violated
                total_pairs += total
    return metrics_from_predictions(
        expected,
        predicted,
        monotonicity_violation_rate=violated_pairs / total_pairs if total_pairs else None,
    )


@dataclass(frozen=True, slots=True)
class MixingSelection:
    """What the Validation grid search decided, and everything it looked at.

    ``reference`` is the ``alpha = 1`` corner -- the frozen stage-1 softmax head,
    i.e. the baseline protocol's own decision measured inside this run. Recording
    it is what makes the accuracy guard and the recall gain readable per seed
    instead of only across two protocols' aggregates.
    """

    mixing: ProbabilityMixing
    validation: EvaluationMetrics
    reference: EvaluationMetrics
    #: False when no grid point cleared the budgets and the reference was kept.
    constraints_satisfied: bool
    monotonicity_violation_rate: float | None
    grid: tuple[dict[str, object], ...]

    def to_dict(self) -> dict[str, object]:
        return {
            "selected": self.mixing.to_dict(),
            "constraints_satisfied": self.constraints_satisfied,
            "monotonicity_violation_rate": self.monotonicity_violation_rate,
            "validation": self.validation.to_dict(),
            "validation_low_engagement": low_engagement_metrics(self.validation.confusion_matrix),
            "reference_validation": self.reference.to_dict(),
            "reference_low_engagement": low_engagement_metrics(self.reference.confusion_matrix),
            "grid": list(self.grid),
        }


def _grid_entry(mixing: ProbabilityMixing, metrics: EvaluationMetrics) -> dict[str, object]:
    low = low_engagement_metrics(metrics.confusion_matrix)
    return {
        "alpha": mixing.alpha,
        "temperature_softmax": mixing.temperature_softmax,
        "temperature_ordinal": mixing.temperature_ordinal,
        "accuracy": metrics.accuracy,
        "macro_f1": metrics.macro_f1,
        "low_engagement_recall": low["recall"],
        "low_engagement_false_positive_rate": low["false_positive_rate"],
        "false_alarms_per_90min": low["false_alarms_per_90min"],
    }


def select_probability_mixing(
    model: EngagementTransformer,
    loader: DataLoader[tuple[Tensor, Tensor]],
    device: torch.device,
    protocol: MixingProtocol,
) -> MixingSelection:
    """Pick the deployed mixing point on Validation, after the head is frozen.

    Both halves' logits are cached from a single pass, so scoring the whole grid
    costs one forward through the encoder rather than one per candidate. That is
    the same saving the dual head buys at inference time, for the same reason.

    The rule is the deployment gate restated on Validation: among the points that
    stay inside the false-alarm budget and do not give up more accuracy than the
    budget allows, take the highest low-engagement recall. Ties break toward
    fewer false alarms, then higher accuracy, then the point closest to the
    baseline corner -- fully ordered, so the choice does not depend on dict order.

    Selecting here and evaluating Test later keeps Test strictly
    post-selection, exactly as the checkpoint choice does.
    """
    if model.config.head != DUAL_HEAD:
        raise ValueError(f"probability mixing requires the {DUAL_HEAD} head")
    model.eval()
    softmax_batches: list[Tensor] = []
    ordinal_batches: list[Tensor] = []
    expected: list[int] = []
    with torch.inference_mode():
        for tokens, labels in loader:
            batch_softmax, batch_ordinal = model.dual_head_logits(tokens.to(device))
            softmax_batches.append(batch_softmax.cpu())
            ordinal_batches.append(batch_ordinal.cpu())
            expected.extend(labels.tolist())
    softmax_logits = torch.cat(softmax_batches)
    ordinal_logits = torch.cat(ordinal_batches)
    # Before any repair, as E0-L records it: a mixture whose ordinal half needed
    # the running minimum on most samples is a different finding from one whose
    # repair never fired. Temperature cannot change this -- it scales all three
    # thresholds by the same positive factor -- so one number covers the grid.
    cumulative = torch.sigmoid(ordinal_logits)
    violation_rate = float((cumulative[:, :-1] < cumulative[:, 1:]).float().mean())

    def score(mixing: ProbabilityMixing) -> EvaluationMetrics:
        log_probabilities = mixed_log_probabilities(softmax_logits, ordinal_logits, mixing)
        return metrics_from_predictions(expected, log_probabilities.argmax(dim=1).tolist())

    reference_mixing = protocol.reference()
    scored = {mixing: score(mixing) for mixing in protocol.points()}
    reference = scored[reference_mixing]

    def admissible(mixing: ProbabilityMixing, metrics: EvaluationMetrics) -> bool:
        low = low_engagement_metrics(metrics.confusion_matrix)
        return (
            low["false_alarms_per_90min"] <= protocol.false_alarm_budget_per_90min
            and reference.accuracy - metrics.accuracy <= protocol.accuracy_drop_budget
        )

    def rank(item: tuple[ProbabilityMixing, EvaluationMetrics]) -> tuple[float, ...]:
        mixing, metrics = item
        low = low_engagement_metrics(metrics.confusion_matrix)
        return (
            -low["recall"],
            low["false_alarms_per_90min"],
            -metrics.accuracy,
            -mixing.alpha,
            mixing.temperature_softmax,
            mixing.temperature_ordinal,
        )

    candidates = [item for item in scored.items() if admissible(*item)]
    selected, metrics = min(candidates, key=rank) if candidates else (reference_mixing, reference)
    model.set_probability_mixing(selected)
    return MixingSelection(
        mixing=selected,
        validation=metrics,
        reference=reference,
        constraints_satisfied=bool(candidates),
        monotonicity_violation_rate=violation_rate,
        grid=tuple(_grid_entry(mixing, scored[mixing]) for mixing in protocol.points()),
    )


def _save_checkpoint(
    path: Path,
    model: nn.Module,
    config: TrainingConfig,
    statistics: FeatureStatistics | None,
    epoch: int,
    validation: EvaluationMetrics,
    *,
    schema: str = SCHEMA_NAME,
) -> None:
    is_transformer = isinstance(model, EngagementTransformer)
    payload: dict[str, Any] = {
        "model_state": model.state_dict(),
        "model_config": model.config.to_dict(),  # type: ignore[attr-defined]
    }
    if is_transformer:
        if statistics is None:
            raise ValueError("transformer checkpoints require feature statistics")
        payload["feature_mean"] = statistics.mean
        payload["feature_std"] = statistics.std
    else:
        from zani_ai.engagement.stgcn import PaperEngagementSTGCN

        # An ST-GCN's graph buffer is fixed but not learned, so it is not part of
        # `model_state`'s gradient-bearing parameters logically; store it
        # explicitly so the checkpoint is self-contained.
        #
        # The two ST-GCN families hold different graphs -- E1's three
        # spatial-configuration partitions `[3,V,V]` and the paper's single
        # `A+I` `[V,V]` -- so each gets its own key. That key is what
        # `load_checkpoint` dispatches on: sharing one would let a checkpoint
        # load and come back as the wrong model.
        if isinstance(model, PaperEngagementSTGCN):
            payload["adjacency"] = model.adjacency.detach().cpu().numpy()
        else:
            payload["partitions"] = model.partitions.detach().cpu().numpy()  # type: ignore[attr-defined]
    payload.update(
        {
            "epoch": epoch,
            "validation": validation.to_dict(),
            "schema": schema,
            "labels": LABELS,
            "model_family": "transformer" if is_transformer else "stgcn",
        }
    )
    # `isinstance` rather than the `is_transformer` flag above: only the real
    # check narrows the type, and the alternative is three `type: ignore`s.
    if isinstance(model, EngagementTransformer) and model.config.head == DUAL_HEAD:
        # `None` for the per-epoch saves, which happen before the grid is
        # searched; filled in by the final rewrite. Written either way so that a
        # dual-head checkpoint always answers the question rather than omitting it.
        payload["probability_mixing"] = (
            model.probability_mixing().to_dict() if model.has_probability_mixing() else None
        )
    torch.save(payload, path)


def load_checkpoint(path: Path, device: str = "cpu") -> nn.Module:
    checkpoint = cast(dict[str, Any], torch.load(path, map_location=device, weights_only=False))
    checkpoint_schema = checkpoint.get("schema")
    if not isinstance(checkpoint_schema, str):
        raise ValueError("checkpoint is missing a schema name")
    # Absent `model_family` means a pre-E1 checkpoint (E0/E0-A/E0-B): always Transformer.
    model_family = checkpoint.get("model_family", "transformer")
    cfg_dict = dict(cast(dict[str, Any], checkpoint["model_config"]))
    if model_family == "stgcn":
        from zani_ai.engagement.stgcn import (
            EngagementSTGCN,
            PaperEngagementSTGCN,
            PaperSTGCNConfig,
            STGCNConfig,
        )

        if "channels" in cfg_dict:
            cfg_dict["channels"] = tuple(cfg_dict["channels"])
        # Which graph the checkpoint carries decides which model it is. See the
        # note in `_save_checkpoint`: `model_family` is "stgcn" for both
        # families, because it is part of the reproducibility identity and
        # splitting it would rewrite E1's configuration hash.
        stgcn_model: nn.Module
        if "adjacency" in checkpoint:
            stgcn_model = PaperEngagementSTGCN(
                torch.as_tensor(checkpoint["adjacency"]), PaperSTGCNConfig(**cfg_dict)
            )
        else:
            stgcn_model = EngagementSTGCN(
                torch.as_tensor(checkpoint["partitions"]), STGCNConfig(**cfg_dict)
            )
        stgcn_model.load_state_dict(checkpoint["model_state"])
        return stgcn_model.to(device)
    if model_family != "transformer":
        raise ValueError(f"checkpoint has unknown model_family: {model_family!r}")
    # `get_schema` only knows token FeatureSchemas, so this validation only
    # applies to the Transformer family (the ST-GCN branch above has already
    # returned for non-token schemas like `landmark_78_v1`).
    try:
        get_schema(checkpoint_schema)
    except ValueError as error:
        raise ValueError(f"checkpoint schema is unknown: {checkpoint_schema!r}") from error
    cfg_dict.setdefault("head", "softmax")
    config = ModelConfig(**cfg_dict)
    model = EngagementTransformer(
        torch.as_tensor(checkpoint["feature_mean"]),
        torch.as_tensor(checkpoint["feature_std"]),
        config=config,
    )
    model.load_state_dict(checkpoint["model_state"])
    recorded_mixing = checkpoint.get("probability_mixing")
    if isinstance(recorded_mixing, dict):
        # Absent (or None) on a checkpoint saved before selection, which is the
        # state `select_probability_mixing` itself loads. `DualHeadMixture` is
        # what refuses to deploy one that never got a point.
        model.set_probability_mixing(ProbabilityMixing(**recorded_mixing))
    return model.to(device)


#: ``state_dict`` prefixes that make up the frozen representation. The
#: classifier head is deliberately absent: stage 2 replaces it.
_BACKBONE_PREFIXES = (
    "feature_mean",
    "feature_std",
    "input_projection.",
    "position_embedding",
    "encoder.",
)

def _stage1_prefixes(head: str) -> tuple[str, ...]:
    """What stage 2 inherits, which depends on which head it is building.

    The dual head also inherits the softmax classifier: its ``alpha = 1`` corner
    has to be the stage-1 model's own decision, which it can only be if the head
    producing it is stage 1's, byte for byte.
    """
    if head == DUAL_HEAD:
        return (*_BACKBONE_PREFIXES, "classifier.")
    return _BACKBONE_PREFIXES
_STAGE1_MODEL_FIELDS = (
    "input_dim",
    "segment_count",
    "d_model",
    "nhead",
    "num_layers",
    "mlp_dim",
    "num_classes",
)


def freeze_stage1_backbone(
    model: EngagementTransformer,
    checkpoint_path: Path,
    computed: FeatureStatistics,
) -> FeatureStatistics:
    """Copy a stage-1 checkpoint's backbone into ``model`` and freeze it.

    Returns the stage-1 feature statistics, which become this run's statistics:
    the frozen encoder was fitted against that normalization, so recomputing it
    -- even to the same values -- would leave the checkpoint describing a
    normalization the weights never saw.

    The statistics are also the check that the two stages share a dataset. They
    are a function of the Train split alone, so a mismatch means the backbone was
    trained on other data and the run is refused rather than silently continued.
    """
    payload = cast(
        dict[str, Any], torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    )
    if payload.get("model_family", "transformer") != "transformer":
        raise ValueError(f"stage-1 checkpoint must be a Transformer checkpoint: {checkpoint_path}")
    recorded = dict(cast(dict[str, Any], payload["model_config"]))
    recorded.setdefault("head", "softmax")
    stage1_config = ModelConfig(**recorded)
    for name in _STAGE1_MODEL_FIELDS:
        if getattr(stage1_config, name) != getattr(model.config, name):
            raise ValueError(
                f"stage-1 checkpoint {name} does not match this protocol's model: "
                f"{checkpoint_path}"
            )
    stage1 = FeatureStatistics(
        np.asarray(payload["feature_mean"], dtype=np.float32),
        np.asarray(payload["feature_std"], dtype=np.float32),
    )
    if stage1.mean.shape != computed.mean.shape or stage1.std.shape != computed.std.shape:
        raise ValueError(
            f"stage-1 checkpoint feature statistics have a wrong shape: {checkpoint_path}"
        )
    if not (
        np.allclose(stage1.mean, computed.mean, rtol=1e-5, atol=1e-6)
        and np.allclose(stage1.std, computed.std, rtol=1e-5, atol=1e-6)
    ):
        raise ValueError(
            "stage-1 checkpoint feature statistics differ from this Train split; "
            f"its backbone was trained on other data: {checkpoint_path}"
        )
    state = cast(dict[str, Tensor], payload["model_state"])
    prefixes = _stage1_prefixes(model.config.head)
    backbone = {key: value for key, value in state.items() if key.startswith(prefixes)}
    incompatible = model.load_state_dict(backbone, strict=False)
    if incompatible.unexpected_keys:
        raise ValueError(
            f"stage-1 checkpoint has unusable backbone keys {tuple(incompatible.unexpected_keys)}: "
            f"{checkpoint_path}"
        )
    absent = tuple(key for key in incompatible.missing_keys if not key.startswith("ordinal_heads."))
    if absent:
        raise ValueError(
            f"stage-1 checkpoint is missing backbone weights {absent}: {checkpoint_path}"
        )
    model.freeze_backbone()
    return stage1


def train_model(
    config: TrainingConfig,
    *,
    evaluate_test: bool = True,
    progress: EpochProgress | None = None,
) -> TrainingResult:
    if config.max_epochs <= 0 or config.patience <= 0:
        raise ValueError("max_epochs and patience must be positive")
    _seed_everything(config.seed, deterministic=config.deterministic)
    manifest_path = config.features_root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"feature manifest not found: {manifest_path}")
    manifest_payload = cast(dict[str, Any], json.loads(manifest_path.read_text(encoding="utf-8")))
    manifest_schema_name = manifest_payload.get("schema")
    if not isinstance(manifest_schema_name, str):
        raise ValueError("feature manifest is missing a schema name")
    # `get_schema` only knows token FeatureSchemas (Transformer manifests).
    # ST-GCN manifests (e.g. `landmark_78_v1`) aren't FeatureSchemas and skip
    # feature statistics entirely, so only look the schema up when it's
    # actually needed.
    schema = get_schema(manifest_schema_name) if config.needs_feature_stats else None
    # This consistency check only makes sense for the default Transformer build
    # path (a custom `build_model` owns its own input-shape validation).
    if (
        schema is not None
        and config.build_model is None
        and schema.token_feature_count != config.model.input_dim
    ):
        raise ValueError(
            "feature manifest token dimension "
            f"({schema.token_feature_count}) does not match ModelConfig.input_dim "
            f"({config.model.input_dim}); set ModelConfig.input_dim to match the "
            f"'{manifest_schema_name}' schema"
        )
    if config.build_model is None and not config.needs_feature_stats:
        raise ValueError("config.build_model is required when needs_feature_stats is False")
    # The two are one decision: a head with no pretrained backbone to freeze
    # trains from scratch, and a frozen backbone with a softmax head is E0 with
    # its encoder switched off. Neither is a protocol we have.
    configured_head = getattr(config.model, "head", "softmax")
    if (configured_head in ("ordinal_binary", DUAL_HEAD)) != (
        config.stage1_checkpoint is not None
    ):
        raise ValueError(
            "the ordinal_binary and dual heads require stage1_checkpoint, and "
            "stage1_checkpoint requires one of those heads"
        )
    # Same argument for the grid: without it the dual head has no deployed output
    # to select, and with it any other head has a grid nothing would search.
    if (configured_head == DUAL_HEAD) != (config.mixing is not None):
        raise ValueError(
            f"the {DUAL_HEAD} head requires mixing, and mixing requires that head"
        )
    datasets = _load_feature_datasets(
        config.features_root,
        include_test=evaluate_test,
        expected_schema=manifest_schema_name,
        array_key=config.array_key,
        array_shape=config.array_shape,
    )
    curriculum_ambiguity = _load_curriculum_ambiguity(config, manifest_path, datasets)
    config.output_dir.mkdir(parents=True, exist_ok=True)
    device = torch.device(config.device)
    statistics: FeatureStatistics | None
    if config.needs_feature_stats:
        assert schema is not None  # guaranteed by the `needs_feature_stats` guard above
        statistics = compute_feature_statistics(
            datasets.train.token_arrays(), token_feature_count=schema.token_feature_count
        )
        if config.build_model is None:
            transformer = EngagementTransformer(
                torch.from_numpy(statistics.mean),
                torch.from_numpy(statistics.std),
                config=config.model,
            ).to(device)
            if config.stage1_checkpoint is not None:
                statistics = freeze_stage1_backbone(
                    transformer, config.stage1_checkpoint, statistics
                )
            model: nn.Module = transformer
        else:
            model = config.build_model(statistics=statistics).to(device)
    else:
        statistics = None
        model = cast(Callable[..., nn.Module], config.build_model)(statistics=None).to(device)
    weights = (
        _class_weights(datasets.train, device, config.class_weighting)
        if config.class_weighting != "none"
        else None
    )
    model_head = getattr(model.config, "head", "softmax")  # type: ignore[attr-defined]
    objective = make_objective(
        model.config,  # type: ignore[attr-defined]
        class_weights=weights if model_head == "softmax" else None,
        loss=config.loss,
        focal_gamma=config.focal_gamma,
        target_encoding=config.target_encoding,
        sord_alpha=config.sord_alpha,
    )
    # Frozen backbone parameters are excluded rather than merely left without
    # gradients, so what stage 2 trains is stated instead of implied. For every
    # single-stage protocol this is every parameter, exactly as before.
    optimizer = torch.optim.Adam(
        [parameter for parameter in model.parameters() if parameter.requires_grad],
        lr=config.learning_rate,
    )
    scheduler = (
        torch.optim.lr_scheduler.StepLR(optimizer, step_size=config.lr_step, gamma=0.1)
        if config.lr_step
        else None
    )
    checkpoint_path = config.output_dir / "best.pt"
    best_score = -1.0
    best_epoch = -1
    stale_epochs = 0
    best_validation: EvaluationMetrics | None = None
    # Selection stays on macro-F1, but QWK and accuracy are recorded per epoch
    # so "would another metric have picked another epoch?" can be answered after
    # the fact, without retraining and without making the selection metric itself
    # ambiguous. Accuracy matters for arXiv:2403.17175, which reports accuracy
    # and does not state how it picked a checkpoint: with the full history, the
    # final-epoch and best-accuracy readings are both recoverable and stay
    # separable from the macro-F1 selection.
    #
    # The learning rate is recorded alongside so a decay schedule can be
    # verified from the artifact rather than assumed from the configuration.
    validation_history: list[dict[str, object]] = []

    def history_entry(epoch: int, validation: EvaluationMetrics) -> dict[str, object]:
        return {
            "epoch": epoch,
            "accuracy": validation.accuracy,
            "macro_f1": validation.macro_f1,
            "quadratic_weighted_kappa": validation.quadratic_weighted_kappa,
            "learning_rate": optimizer.param_groups[0]["lr"],
        }

    if curriculum_ambiguity is None:
        train_loader = _loader(datasets.train, config, shuffle=True)
        valid_loader = _loader(datasets.valid, config, shuffle=False)
        for epoch in range(config.max_epochs):
            _train_epoch(model, train_loader, optimizer, objective, device)
            validation = evaluate_model(model, valid_loader, device)
            # Before `scheduler.step()`: the entry has to name the rate this
            # epoch trained at, not the one the next epoch will use.
            validation_history.append(history_entry(epoch, validation))
            if progress is not None:
                progress(epoch, validation, stale_epochs)
            if scheduler is not None:
                scheduler.step()
            if validation.macro_f1 > best_score:
                best_score = validation.macro_f1
                best_epoch = epoch
                best_validation = validation
                stale_epochs = 0
                _save_checkpoint(
                    checkpoint_path,
                    model,
                    config,
                    statistics,
                    epoch,
                    validation,
                    schema=manifest_schema_name,
                )
            else:
                stale_epochs += 1
                if stale_epochs >= config.patience:
                    break
    else:
        valid_loader = _loader(datasets.valid, config, shuffle=False)
        reliable_indices = [
            index for index, ambiguous in enumerate(curriculum_ambiguity) if not ambiguous
        ]
        warmup_loader = _curriculum_loader(
            Subset(datasets.train, reliable_indices), config, shuffle=True
        )
        mixed_loader = _curriculum_loader(
            ReliabilityFeatureDataset(datasets.train, curriculum_ambiguity),
            config,
            shuffle=True,
        )
        curriculum_objective = AdjacentSmoothingObjective(
            num_classes=len(LABELS), neighbor_mass=config.ambiguous_neighbor_mass
        )
        for epoch in range(config.reliable_warmup_epochs):
            _train_epoch(model, warmup_loader, optimizer, objective, device)
            validation = evaluate_model(model, valid_loader, device)
            validation_history.append(
                {**history_entry(epoch, validation), "curriculum_stage": "reliable_warmup"}
            )
            if progress is not None:
                # Warm-up cannot stop early -- every one of these epochs runs --
                # so nothing has gone stale yet.
                progress(epoch, validation, 0)

        for stage_epoch in range(config.max_epochs):
            epoch = config.reliable_warmup_epochs + stage_epoch
            _train_curriculum_epoch(model, mixed_loader, optimizer, curriculum_objective, device)
            validation = evaluate_model(model, valid_loader, device)
            validation_history.append(
                {**history_entry(epoch, validation), "curriculum_stage": "mixed"}
            )
            if progress is not None:
                progress(epoch, validation, stale_epochs)
            if validation.macro_f1 > best_score:
                best_score = validation.macro_f1
                best_epoch = epoch
                best_validation = validation
                stale_epochs = 0
                _save_checkpoint(
                    checkpoint_path,
                    model,
                    config,
                    statistics,
                    epoch,
                    validation,
                    schema=manifest_schema_name,
                )
            else:
                stale_epochs += 1
                if stale_epochs >= config.patience:
                    break
    if best_validation is None:
        raise RuntimeError("training completed without a validation checkpoint")
    mixing_selection: MixingSelection | None = None
    if config.mixing is not None:
        # Against the *selected* checkpoint, not the last one trained: the
        # mixture that ships has to be chosen for the weights that ship.
        selected_model = load_checkpoint(checkpoint_path, config.device)
        assert isinstance(selected_model, EngagementTransformer)
        mixing_selection = select_probability_mixing(
            selected_model,
            _loader(datasets.valid, config, shuffle=False),
            device,
            config.mixing,
        )
        # Rewritten so the mixing point travels with the weights it was chosen
        # for. `validation`/`epoch` stay as the epoch loop recorded them -- the
        # checkpoint was selected on the ordinal head's macro-F1 and saying
        # otherwise afterwards would misreport why this epoch won.
        _save_checkpoint(
            checkpoint_path,
            selected_model,
            config,
            statistics,
            best_epoch,
            best_validation,
            schema=manifest_schema_name,
        )
    test_metrics: EvaluationMetrics | None = None
    if evaluate_test:
        if datasets.test is None:
            raise RuntimeError("Test dataset was not loaded for Test evaluation")
        # `deployment_view` is what makes the dual head's Test numbers the mixed
        # decision rather than its ordinal half; every other head passes through.
        best_model = deployment_view(load_checkpoint(checkpoint_path, config.device))
        test_metrics = evaluate_model(
            best_model, _loader(datasets.test, config, shuffle=False), device
        )
    metrics_path = config.output_dir / "metrics.json"
    training_payload = dict(asdict(config))
    training_payload["features_root"] = str(config.features_root)
    training_payload["output_dir"] = str(config.output_dir)
    if config.reliability_manifest is not None:
        training_payload["reliability_manifest"] = str(config.reliability_manifest)
    if config.stage1_checkpoint is not None:
        training_payload["stage1_checkpoint"] = str(config.stage1_checkpoint)
    training_payload["model"] = model.config.to_dict()  # type: ignore[attr-defined]
    if training_payload.get("build_model") is not None:
        # `build_model` is a callable and not JSON-serializable; record a
        # human-readable name instead of the raw function/object.
        training_payload["build_model"] = getattr(
            config.build_model, "__name__", repr(config.build_model)
        )
    model_family = "transformer" if isinstance(model, EngagementTransformer) else "stgcn"
    payload = {
        "schema": manifest_schema_name,
        "labels": LABELS,
        "selection_metric": "validation_macro_f1",
        "best_epoch": best_epoch,
        "model_family": model_family,
        "validation": best_validation.to_dict(),
        "validation_history": validation_history,
        "training": training_payload,
    }
    if mixing_selection is not None:
        payload["probability_mixing"] = mixing_selection.to_dict()
    if test_metrics is None:
        payload["test_evaluation"] = {
            "status": "deferred",
            "reason": "Test evaluation is deferred by protocol.",
        }
    else:
        payload["test"] = test_metrics.to_dict()
    metrics_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return TrainingResult(
        checkpoint_path,
        metrics_path,
        best_epoch,
        best_validation,
        test_metrics,
        mixing=mixing_selection,
    )


__all__ = [
    "CLASS_WEIGHTING_SCHEMES",
    "LOSS_SCHEMES",
    "SAMPLER_SCHEMES",
    "TARGET_ENCODINGS",
    "AdjacentSmoothingObjective",
    "CoralObjective",
    "EvaluationMetrics",
    "FeatureStatistics",
    "FocalObjective",
    "MixingSelection",
    "Objective",
    "OrdinalBinaryObjective",
    "SoftmaxObjective",
    "SordObjective",
    "TrainingConfig",
    "TrainingResult",
    "compute_feature_statistics",
    "evaluate_model",
    "freeze_stage1_backbone",
    "load_checkpoint",
    "make_objective",
    "metrics_from_predictions",
    "ordinal_quality",
    "select_probability_mixing",
    "train_model",
    "validate_manifest_completion",
]
