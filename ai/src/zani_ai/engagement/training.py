from __future__ import annotations

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
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score
from torch import Tensor, nn
from torch.utils.data import DataLoader, Dataset

from zani_ai.engagement.contracts import LABELS, SplitName
from zani_ai.engagement.features import SCHEMA_NAME, TOKEN_FEATURE_COUNT, get_schema
from zani_ai.engagement.model import EngagementTransformer, ModelConfig


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
    ) -> None:
        if not entries:
            raise ValueError("feature split is empty")
        self.entries = entries
        self.token_feature_count = token_feature_count

    def __len__(self) -> int:
        return len(self.entries)

    def __getitem__(self, index: int) -> tuple[Tensor, Tensor]:
        entry = self.entries[index]
        with np.load(entry.feature_path, allow_pickle=False) as cache:
            tokens = np.asarray(cache["tokens"], dtype=np.float32)
        if tokens.shape != (20, self.token_feature_count) or not np.isfinite(tokens).all():
            raise ValueError(f"invalid cached tokens: {entry.feature_path}")
        return torch.from_numpy(tokens), torch.tensor(entry.label_index, dtype=torch.long)

    def token_arrays(self) -> Iterator[NDArray[np.float32]]:
        for entry in self.entries:
            with np.load(entry.feature_path, allow_pickle=False) as cache:
                yield np.asarray(cache["tokens"], dtype=np.float32)


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
    use_class_weights: bool = False
    num_workers: int = 0
    deterministic: bool = False
    model: ModelConfig = field(default_factory=ModelConfig)


@dataclass(frozen=True, slots=True)
class EvaluationMetrics:
    accuracy: float
    macro_f1: float
    confusion_matrix: list[list[int]]
    classification_report: dict[str, object]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


@dataclass(frozen=True, slots=True)
class TrainingResult:
    checkpoint_path: Path
    metrics_path: Path
    best_epoch: int
    validation: EvaluationMetrics
    test: EvaluationMetrics | None


type EpochProgress = Callable[[int, EvaluationMetrics], None]


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
        or not math.isclose(
            excluded_fraction, expected_fraction, rel_tol=0, abs_tol=1e-12
        )
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
    root: Path, *, include_test: bool = True, expected_schema: str = SCHEMA_NAME
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
    token_feature_count = get_schema(expected_schema).token_feature_count
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
        CachedFeatureDataset(tuple(grouped["train"]), token_feature_count=token_feature_count),
        CachedFeatureDataset(tuple(grouped["valid"]), token_feature_count=token_feature_count),
        CachedFeatureDataset(tuple(grouped["test"]), token_feature_count=token_feature_count)
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


def _loader(
    dataset: CachedFeatureDataset,
    config: TrainingConfig,
    *,
    shuffle: bool,
) -> DataLoader[tuple[Tensor, Tensor]]:
    generator = torch.Generator().manual_seed(config.seed)
    return DataLoader(
        dataset,
        batch_size=config.batch_size,
        shuffle=shuffle,
        num_workers=config.num_workers,
        generator=generator,
    )


def _class_weights(dataset: CachedFeatureDataset, device: torch.device) -> Tensor:
    counts = np.bincount([entry.label_index for entry in dataset.entries], minlength=len(LABELS))
    if np.any(counts == 0):
        raise ValueError("class weighting requires every class in the training split")
    weights = len(dataset) / (len(LABELS) * counts)
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


def make_objective(config: ModelConfig, class_weights: Tensor | None = None) -> Objective:
    if config.head == "coral":
        return CoralObjective(config.num_classes)
    return SoftmaxObjective(weight=class_weights)


def _train_epoch(
    model: EngagementTransformer,
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


def evaluate_model(
    model: EngagementTransformer,
    loader: DataLoader[tuple[Tensor, Tensor]],
    device: torch.device,
) -> EvaluationMetrics:
    model.eval()
    objective = make_objective(model.config)
    expected: list[int] = []
    predicted: list[int] = []
    with torch.inference_mode():
        for tokens, labels in loader:
            logits = model(tokens.to(device))
            expected.extend(labels.tolist())
            predicted.extend(objective.predict(logits).cpu().tolist())
    report = classification_report(
        expected,
        predicted,
        labels=list(range(len(LABELS))),
        target_names=list(LABELS),
        output_dict=True,
        zero_division=0,
    )
    return EvaluationMetrics(
        accuracy=float(accuracy_score(expected, predicted)),
        macro_f1=float(f1_score(expected, predicted, labels=list(range(4)), average="macro")),
        confusion_matrix=confusion_matrix(expected, predicted, labels=list(range(4))).tolist(),
        classification_report=cast(dict[str, object], report),
    )


def _save_checkpoint(
    path: Path,
    model: EngagementTransformer,
    config: TrainingConfig,
    statistics: FeatureStatistics,
    epoch: int,
    validation: EvaluationMetrics,
    *,
    schema: str = SCHEMA_NAME,
) -> None:
    torch.save(
        {
            "model_state": model.state_dict(),
            "model_config": config.model.to_dict(),
            "feature_mean": statistics.mean,
            "feature_std": statistics.std,
            "epoch": epoch,
            "validation": validation.to_dict(),
            "schema": schema,
            "labels": LABELS,
        },
        path,
    )


def load_checkpoint(path: Path, device: str = "cpu") -> EngagementTransformer:
    checkpoint = cast(dict[str, Any], torch.load(path, map_location=device, weights_only=False))
    checkpoint_schema = checkpoint.get("schema")
    if not isinstance(checkpoint_schema, str):
        raise ValueError("checkpoint is missing a schema name")
    try:
        get_schema(checkpoint_schema)
    except ValueError as error:
        raise ValueError(f"checkpoint schema is unknown: {checkpoint_schema!r}") from error
    cfg_dict = dict(cast(dict[str, Any], checkpoint["model_config"]))
    cfg_dict.setdefault("head", "softmax")
    config = ModelConfig(**cfg_dict)
    model = EngagementTransformer(
        torch.as_tensor(checkpoint["feature_mean"]),
        torch.as_tensor(checkpoint["feature_std"]),
        config=config,
    )
    model.load_state_dict(checkpoint["model_state"])
    return model.to(device)


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
    schema = get_schema(manifest_schema_name)
    if schema.token_feature_count != config.model.input_dim:
        raise ValueError(
            "feature manifest token dimension "
            f"({schema.token_feature_count}) does not match ModelConfig.input_dim "
            f"({config.model.input_dim}); set ModelConfig.input_dim to match the "
            f"'{manifest_schema_name}' schema"
        )
    datasets = _load_feature_datasets(
        config.features_root, include_test=evaluate_test, expected_schema=manifest_schema_name
    )
    config.output_dir.mkdir(parents=True, exist_ok=True)
    statistics = compute_feature_statistics(
        datasets.train.token_arrays(), token_feature_count=schema.token_feature_count
    )
    device = torch.device(config.device)
    model = EngagementTransformer(
        torch.from_numpy(statistics.mean), torch.from_numpy(statistics.std), config=config.model
    ).to(device)
    weights = _class_weights(datasets.train, device) if config.use_class_weights else None
    objective = make_objective(
        config.model, class_weights=weights if config.model.head == "softmax" else None
    )
    optimizer = torch.optim.Adam(model.parameters(), lr=config.learning_rate)
    train_loader = _loader(datasets.train, config, shuffle=True)
    valid_loader = _loader(datasets.valid, config, shuffle=False)
    checkpoint_path = config.output_dir / "best.pt"
    best_score = -1.0
    best_epoch = -1
    stale_epochs = 0
    best_validation: EvaluationMetrics | None = None
    for epoch in range(config.max_epochs):
        _train_epoch(model, train_loader, optimizer, objective, device)
        validation = evaluate_model(model, valid_loader, device)
        if progress is not None:
            progress(epoch, validation)
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
    test_metrics: EvaluationMetrics | None = None
    if evaluate_test:
        if datasets.test is None:
            raise RuntimeError("Test dataset was not loaded for Test evaluation")
        best_model = load_checkpoint(checkpoint_path, config.device)
        test_metrics = evaluate_model(
            best_model, _loader(datasets.test, config, shuffle=False), device
        )
    metrics_path = config.output_dir / "metrics.json"
    payload = {
        "schema": manifest_schema_name,
        "labels": LABELS,
        "selection_metric": "validation_macro_f1",
        "best_epoch": best_epoch,
        "validation": best_validation.to_dict(),
        "training": {
            **asdict(config),
            "features_root": str(config.features_root),
            "output_dir": str(config.output_dir),
            "model": config.model.to_dict(),
        },
    }
    if test_metrics is None:
        payload["test_evaluation"] = {
            "status": "deferred",
            "reason": "Test evaluation is deferred by protocol.",
        }
    else:
        payload["test"] = test_metrics.to_dict()
    metrics_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return TrainingResult(
        checkpoint_path, metrics_path, best_epoch, best_validation, test_metrics
    )


__all__ = [
    "CoralObjective",
    "EvaluationMetrics",
    "FeatureStatistics",
    "Objective",
    "SoftmaxObjective",
    "TrainingConfig",
    "TrainingResult",
    "compute_feature_statistics",
    "evaluate_model",
    "load_checkpoint",
    "make_objective",
    "train_model",
    "validate_manifest_completion",
]
