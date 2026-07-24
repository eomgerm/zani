from __future__ import annotations

import hashlib
import json
import os
import platform
import statistics
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import cast

# cuBLAS requires this workspace setting for deterministic CUDA matrix multiplication.
# It must be present before the first CUDA operation in this process.
os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import torch
from torch import nn

from zani_ai.engagement.export import DeploymentMetadata, export_onnx
from zani_ai.engagement.features import SCHEMA_98, SCHEMA_132, FeatureSchema
from zani_ai.engagement.landmark_graph import GRAPH_VERSION, load_graph
from zani_ai.engagement.model import ModelConfig
from zani_ai.engagement.stgcn import EngagementSTGCN, STGCNConfig
from zani_ai.engagement.training import (
    EvaluationMetrics,
    FeatureStatistics,
    TrainingConfig,
    load_checkpoint,
    train_model,
    validate_manifest_completion,
)

E0_SEEDS = (42, 43, 44, 45, 46)
_SPLITS = {"train", "valid", "test"}
_CUBLAS_CONFIGS = {":4096:8", ":16:8"}


@dataclass(frozen=True, slots=True)
class E0ExperimentResult:
    summary_path: Path
    completed_seeds: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class ExperimentSpec:
    """Fully describes a reproducible experiment protocol (e.g. E0, E0-A, E1).

    ``schema`` is a token-based ``FeatureSchema`` for the Transformer family
    (E0/E0-A/E0-B); non-token representations (e.g. E1's ST-GCN landmark
    sequences) have no ``FeatureSchema`` and pass ``schema=None`` together
    with ``representation_name`` instead. Use :attr:`schema_name` to get the
    manifest/summary schema string regardless of which one is set.

    The fields below ``seeds`` are all optional and default to the existing
    Transformer behavior, so E0/E0-A/E0-B (which only pass ``protocol``,
    ``schema`` and ``model_config``) are completely unaffected.
    """

    protocol: str
    schema: FeatureSchema | None
    model_config: ModelConfig | STGCNConfig
    seeds: tuple[int, ...] = E0_SEEDS
    # E1 (non-Transformer) hooks; None/defaults reproduce the Transformer path.
    representation_name: str | None = None
    build_model: Callable[..., nn.Module] | None = None
    needs_feature_stats: bool = True
    learning_rate: float = 1e-4
    batch_size: int = 32
    max_epochs: int = 200
    lr_step: int | None = None
    array_key: str = "tokens"
    array_shape: tuple[int, ...] | None = None

    @property
    def schema_name(self) -> str:
        """The manifest/summary schema name, for token and non-token specs alike."""
        if self.representation_name is not None:
            return self.representation_name
        if self.schema is None:
            raise ValueError(f"{self.protocol} spec has neither schema nor representation_name")
        return self.schema.name


E0_SPEC = ExperimentSpec("E0", SCHEMA_98, ModelConfig(input_dim=98))
E0A_SPEC = ExperimentSpec("E0-A", SCHEMA_132, ModelConfig(input_dim=132))
E0B_SPEC = ExperimentSpec("E0-B", SCHEMA_98, ModelConfig(input_dim=98, head="coral"))

# Resolved relative to the current working directory at train time (Task 7);
# it need not exist yet for the spec itself to be defined/imported.
GRAPH_PATH = Path("datasets/processed/engagenet/landmark_78_v1_graph.npz")


def build_stgcn_model(statistics: FeatureStatistics | None = None) -> nn.Module:
    """E1 ``TrainingConfig.build_model`` factory: loads the fixed landmark graph.

    Ignores ``statistics`` (ST-GCN needs no feature normalization statistics;
    see ``ExperimentSpec.needs_feature_stats=False`` on ``E1_SPEC``).
    """
    del statistics
    _, partitions = load_graph(GRAPH_PATH)
    return EngagementSTGCN(torch.as_tensor(partitions), STGCNConfig())


E1_SPEC = ExperimentSpec(
    "E1",
    None,
    STGCNConfig(),
    seeds=E0_SEEDS,
    representation_name=GRAPH_VERSION,
    build_model=build_stgcn_model,
    needs_feature_stats=False,
    learning_rate=1e-3,
    batch_size=16,
    max_epochs=300,
    lr_step=100,
    array_key="sequence",
    array_shape=(3, 100, 78),
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical_hash(payload: dict[str, object]) -> str:
    encoded = json.dumps(
        payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _write_json_atomic(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        temporary.write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _validate_manifest(features_root: Path, spec: ExperimentSpec) -> tuple[Path, str]:
    manifest_path = features_root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"feature manifest not found: {manifest_path}")
    try:
        manifest_bytes = manifest_path.read_bytes()
        payload = json.loads(manifest_bytes.decode("utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError(f"invalid feature manifest JSON: {manifest_path}") from error
    if not isinstance(payload, dict):
        raise ValueError("feature manifest must be a JSON object")
    if payload.get("schema") != spec.schema_name:
        raise ValueError(f"feature manifest schema must be {spec.schema_name}")
    included, excluded = validate_manifest_completion(payload)
    splits: set[str] = set()
    for index, item in enumerate(included):
        if not isinstance(item, dict) or not isinstance(item.get("split"), str):
            raise ValueError(f"invalid included entry at index {index}")
        split = cast(str, item["split"])
        if split not in _SPLITS:
            raise ValueError(f"unknown feature split: {split}")
        if not isinstance(item.get("clip_id"), str) or not item["clip_id"]:
            raise ValueError(f"invalid clip_id in included entry at index {index}")
        if split != "test":
            label_index = item.get("label_index")
            if not isinstance(label_index, int) or isinstance(label_index, bool):
                raise ValueError(f"invalid label_index in included entry at index {index}")
            if label_index not in range(4):
                raise ValueError(
                    f"label_index out of range in included entry at index {index}"
                )
        feature_path_value = item.get("feature_path")
        if not isinstance(feature_path_value, str) or not feature_path_value:
            raise ValueError(f"invalid feature_path in included entry at index {index}")
        feature_path = features_root / feature_path_value
        try:
            feature_path.resolve().relative_to(features_root.resolve())
        except ValueError as error:
            raise ValueError(
                f"feature_path escapes the feature root in included entry at index {index}"
            ) from error
        if not feature_path.is_file():
            raise FileNotFoundError(f"cached feature not found: {feature_path}")
        fingerprint = item.get("source_fingerprint")
        if not isinstance(fingerprint, str) or not fingerprint:
            raise ValueError(
                f"invalid source_fingerprint in included entry at index {index}"
            )
        splits.add(split)
    for index, item in enumerate(excluded):
        if not isinstance(item, dict):
            raise ValueError(f"invalid excluded entry at index {index}")
        if item.get("split") not in _SPLITS:
            raise ValueError(f"invalid split in excluded entry at index {index}")
        if not isinstance(item.get("clip_id"), str) or not item["clip_id"]:
            raise ValueError(f"invalid clip_id in excluded entry at index {index}")
        if not isinstance(item.get("reason"), str) or not item["reason"]:
            raise ValueError(f"invalid reason in excluded entry at index {index}")
    if splits != _SPLITS:
        missing = ", ".join(sorted(_SPLITS - splits))
        raise ValueError(f"feature manifest is missing non-empty split(s): {missing}")
    return manifest_path, hashlib.sha256(manifest_bytes).hexdigest()


def _build_configuration(spec: ExperimentSpec, device: str) -> dict[str, object]:
    if spec.build_model is None:
        # Transformer path (E0/E0-A/E0-B). `spec.learning_rate`/`batch_size`/
        # `max_epochs` default to the exact literals this used to hardcode, so
        # this dict (and its hash) is byte-identical to before for those specs.
        assert spec.schema is not None
        configuration: dict[str, object] = {
            "feature_schema": spec.schema.name,
            "input_shape": ["batch", 20, spec.schema.token_feature_count],
            "seeds": list(spec.seeds),
            "optimizer": "Adam",
            "learning_rate": spec.learning_rate,
            "batch_size": spec.batch_size,
            "maximum_epochs": spec.max_epochs,
            "early_stopping": {
                "metric": "validation_macro_f1",
                "mode": "max",
                "patience": 20,
            },
            "class_weighting": False,
            "model": {
                **spec.model_config.to_dict(),
                "learned_position_count": 20,
                "pooling": "max",
                "classifier_dimensions": [256, 128, 4],
            },
            "device": device,
            "deterministic_algorithms": True,
            "num_workers": 0,
        }
        if getattr(spec.model_config, "head", "softmax") == "coral":
            configuration["loss"] = "coral_bce"
        return configuration

    # ST-GCN path (E1 and other non-token representations).
    return {
        "representation": spec.schema_name,
        "model_family": "stgcn",
        "seeds": list(spec.seeds),
        "optimizer": "Adam",
        "learning_rate": spec.learning_rate,
        "batch_size": spec.batch_size,
        "maximum_epochs": spec.max_epochs,
        "lr_step": spec.lr_step,
        "early_stopping": {
            "metric": "validation_macro_f1",
            "mode": "max",
            "patience": 20,
        },
        "class_weighting": False,
        "model": spec.model_config.to_dict(),
        "device": device,
        "deterministic_algorithms": True,
        "num_workers": 0,
    }


def _environment(device: str, spec: ExperimentSpec) -> dict[str, object]:
    cuda_available = torch.cuda.is_available()
    if device == "cuda" and not cuda_available:
        raise RuntimeError(
            f"{spec.protocol} requested device=cuda, but PyTorch reports CUDA unavailable"
        )
    cuda_device: dict[str, object] | None = None
    if device == "cuda":
        index = torch.cuda.current_device()
        cuda_device = {
            "index": index,
            "name": torch.cuda.get_device_name(index),
            "capability": list(torch.cuda.get_device_capability(index)),
        }
    return {
        "python": platform.python_version(),
        "pytorch": str(torch.__version__),
        "cuda_runtime": torch.version.cuda,
        "cuda_available": cuda_available,
        "requested_device": device,
        "cuda_device": cuda_device,
        "cublas_workspace_config": os.environ.get("CUBLAS_WORKSPACE_CONFIG"),
    }


def _enable_strict_determinism(device: str) -> None:
    cublas_config = os.environ.get("CUBLAS_WORKSPACE_CONFIG")
    if device == "cuda" and cublas_config not in _CUBLAS_CONFIGS:
        raise RuntimeError(
            "strict CUDA determinism requires CUBLAS_WORKSPACE_CONFIG=:4096:8 or :16:8"
        )
    try:
        torch.backends.cudnn.benchmark = False
        torch.backends.cudnn.deterministic = True
        torch.use_deterministic_algorithms(True, warn_only=False)
    except Exception as error:
        raise RuntimeError("PyTorch strict deterministic execution is unsupported") from error
    if not torch.are_deterministic_algorithms_enabled():
        raise RuntimeError("PyTorch strict deterministic execution could not be enabled")


def _empty_summary(
    manifest_path: Path,
    manifest_sha256: str,
    configuration: dict[str, object],
    environment: dict[str, object],
    spec: ExperimentSpec,
) -> dict[str, object]:
    return {
        "protocol": spec.protocol,
        "status": "in_progress",
        "feature_manifest": {
            "path": str(manifest_path.resolve()),
            "schema": spec.schema_name,
            "sha256": manifest_sha256,
        },
        "configuration": configuration,
        "configuration_sha256": _canonical_hash(configuration),
        "environment": environment,
        "test_evaluation": {
            "status": "deferred",
            "reason": f"Test evaluation is deferred by the {spec.protocol} protocol.",
        },
        "seeds": [],
        "aggregate": _aggregate([]),
    }


def _load_summary(path: Path, spec: ExperimentSpec) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ValueError(f"invalid {spec.protocol} summary JSON: {path}") from error
    if not isinstance(payload, dict):
        raise ValueError(f"{spec.protocol} summary must be a JSON object: {path}")
    return cast(dict[str, object], payload)


def _validate_summary_identity(
    summary: dict[str, object],
    manifest_sha256: str,
    configuration: dict[str, object],
    environment: dict[str, object],
    spec: ExperimentSpec,
) -> None:
    manifest = summary.get("feature_manifest")
    actual_manifest_hash = manifest.get("sha256") if isinstance(manifest, dict) else None
    if actual_manifest_hash != manifest_sha256:
        raise ValueError(
            f"existing {spec.protocol} summary uses a different feature manifest; "
            "choose a new output directory"
        )
    if summary.get("configuration") != configuration:
        raise ValueError(
            f"existing {spec.protocol} summary uses a different configuration; "
            "choose a new output directory"
        )
    if summary.get("configuration_sha256") != _canonical_hash(configuration):
        raise ValueError(
            f"existing {spec.protocol} summary has an invalid configuration fingerprint"
        )
    if summary.get("environment") != environment:
        raise ValueError(
            f"existing {spec.protocol} summary was created in a different runtime environment; "
            "choose a new output directory"
        )
    seeds = summary.get("seeds")
    if not isinstance(seeds, list):
        raise ValueError(f"existing {spec.protocol} summary has an invalid seeds list")
    recorded_seeds: list[int] = []
    for item in seeds:
        if not isinstance(item, dict) or item.get("seed") not in spec.seeds:
            raise ValueError(f"existing {spec.protocol} summary contains an invalid seed record")
        recorded_seeds.append(cast(int, item["seed"]))
    if len(recorded_seeds) != len(set(recorded_seeds)):
        raise ValueError(f"existing {spec.protocol} summary contains duplicate seed records")


def _seed_paths(output_dir: Path, seed: int) -> dict[str, Path]:
    seed_dir = output_dir / f"seed-{seed}"
    return {
        "checkpoint": seed_dir / "best.pt",
        "metrics": seed_dir / "metrics.json",
        "onnx_model": seed_dir / "onnx" / "engagement.onnx",
        "onnx_metadata": seed_dir / "onnx" / "engagement.metadata.json",
    }


def _artifact_records(
    output_dir: Path, paths: dict[str, Path]
) -> dict[str, dict[str, object]]:
    records: dict[str, dict[str, object]] = {}
    for name, path in paths.items():
        if not path.is_file():
            raise FileNotFoundError(f"seed artifact not found: {path}")
        size_bytes = path.stat().st_size
        if size_bytes <= 0:
            raise RuntimeError(f"seed artifact is empty: {path}")
        records[name] = {
            "path": path.relative_to(output_dir).as_posix(),
            "sha256": _sha256(path),
            "size_bytes": size_bytes,
        }
    return records


def _assert_manifest_unchanged(
    path: Path, expected_sha256: str, boundary: str, spec: ExperimentSpec
) -> None:
    try:
        actual_sha256 = _sha256(path)
    except OSError as error:
        raise RuntimeError(f"feature manifest unavailable {boundary}: {path}") from error
    if actual_sha256 != expected_sha256:
        raise RuntimeError(
            f"feature manifest changed {boundary}; "
            f"refusing to complete an incompatible {spec.protocol} seed"
        )


def _seed_record(summary: dict[str, object], seed: int) -> dict[str, object] | None:
    seeds = cast(list[object], summary["seeds"])
    for item in seeds:
        if isinstance(item, dict) and item.get("seed") == seed:
            return cast(dict[str, object], item)
    return None


def _seed_is_complete(
    record: dict[str, object] | None,
    *,
    seed: int,
    output_dir: Path,
    manifest_sha256: str,
    configuration: dict[str, object],
    spec: ExperimentSpec,
) -> tuple[bool, str]:
    if record is None:
        return False, "not recorded in summary"
    paths = _seed_paths(output_dir, seed)
    if record.get("status") != "complete":
        return False, "summary status is not complete"
    if record.get("feature_manifest_sha256") != manifest_sha256:
        return False, "feature manifest fingerprint does not match"
    if record.get("configuration_sha256") != _canonical_hash(configuration):
        return False, "configuration fingerprint does not match"
    artifacts = record.get("artifacts")
    if not isinstance(artifacts, dict):
        return False, "artifact integrity records are missing"
    for name, path in paths.items():
        artifact = artifacts.get(name)
        if not isinstance(artifact, dict):
            return False, f"{name} integrity record is missing"
        if artifact.get("path") != path.relative_to(output_dir).as_posix():
            return False, f"{name} path does not match"
        expected_hash = artifact.get("sha256")
        expected_size = artifact.get("size_bytes")
        if (
            not isinstance(expected_hash, str)
            or len(expected_hash) != 64
            or not isinstance(expected_size, int)
            or isinstance(expected_size, bool)
            or expected_size <= 0
        ):
            return False, f"{name} integrity record is invalid"
        if not path.is_file():
            return False, f"{name} is missing"
        if path.stat().st_size != expected_size or _sha256(path) != expected_hash:
            return False, f"{name} integrity check failed"
    try:
        metrics = json.loads(paths["metrics"].read_text(encoding="utf-8"))
    except (json.JSONDecodeError, UnicodeDecodeError, OSError):
        return False, "metrics.json is unreadable"
    if not isinstance(metrics, dict) or "test" in metrics:
        return False, "metrics.json is not validation-only"
    test_evaluation = metrics.get("test_evaluation")
    if not isinstance(test_evaluation, dict) or test_evaluation.get("status") != "deferred":
        return False, "metrics.json does not defer Test evaluation"
    experiment = metrics.get("experiment")
    if not isinstance(experiment, dict):
        return False, f"metrics.json has no {spec.protocol} identity"
    if (
        experiment.get("protocol") != spec.protocol
        or experiment.get("seed") != seed
        or experiment.get("feature_manifest_sha256") != manifest_sha256
        or experiment.get("configuration") != configuration
        or experiment.get("configuration_sha256") != _canonical_hash(configuration)
    ):
        return False, f"metrics.json {spec.protocol} identity does not match"
    validation = metrics.get("validation")
    recorded_validation = record.get("validation")
    if not isinstance(validation, dict) or not isinstance(recorded_validation, dict):
        return False, "validation metrics are missing"
    if (
        validation.get("accuracy") != recorded_validation.get("accuracy")
        or validation.get("macro_f1") != recorded_validation.get("macro_f1")
        or metrics.get("best_epoch") != record.get("best_epoch")
    ):
        return False, "recorded validation result does not match metrics.json"
    return True, "complete"


def _aggregate(seed_records: list[dict[str, object]]) -> dict[str, object]:
    accuracies: list[float] = []
    macro_f1s: list[float] = []
    for record in seed_records:
        validation = record.get("validation")
        if isinstance(validation, dict):
            accuracies.append(float(validation["accuracy"]))
            macro_f1s.append(float(validation["macro_f1"]))

    def summarize(values: list[float]) -> dict[str, float | None]:
        return {
            "mean": statistics.fmean(values) if values else None,
            "sample_standard_deviation": statistics.stdev(values) if len(values) >= 2 else None,
        }

    return {
        "completed_seed_count": len(seed_records),
        "validation_accuracy": summarize(accuracies),
        "validation_macro_f1": summarize(macro_f1s),
    }


def _update_summary(summary: dict[str, object], spec: ExperimentSpec) -> None:
    records = [
        cast(dict[str, object], item)
        for item in cast(list[object], summary["seeds"])
        if isinstance(item, dict)
    ]
    records.sort(key=lambda item: int(item["seed"]))
    summary["seeds"] = records
    summary["aggregate"] = _aggregate(records)
    summary["status"] = (
        "complete"
        if [item["seed"] for item in records] == list(spec.seeds)
        else "in_progress"
    )


def reproduce_experiment(
    spec: ExperimentSpec, features_root: Path, output_dir: Path, *, device: str
) -> E0ExperimentResult:
    if device not in {"cpu", "cuda"}:
        raise ValueError(f"{spec.protocol} device must be 'cpu' or 'cuda'")
    manifest_path, manifest_sha256 = _validate_manifest(features_root, spec)
    configuration = _build_configuration(spec, device)
    environment = _environment(device, spec)
    _enable_strict_determinism(device)

    output_dir.mkdir(parents=True, exist_ok=True)
    summary_path = output_dir / "summary.json"
    if summary_path.is_file():
        summary = _load_summary(summary_path, spec)
        _validate_summary_identity(summary, manifest_sha256, configuration, environment, spec)
    else:
        summary = _empty_summary(
            manifest_path, manifest_sha256, configuration, environment, spec
        )
        _write_json_atomic(summary_path, summary)

    for seed in spec.seeds:
        existing = _seed_record(summary, seed)
        complete, reason = _seed_is_complete(
            existing,
            seed=seed,
            output_dir=output_dir,
            manifest_sha256=manifest_sha256,
            configuration=configuration,
            spec=spec,
        )
        if complete:
            print(f"{spec.protocol} seed={seed} resume=complete", flush=True)
            continue
        if existing is not None:
            print(f"{spec.protocol} seed={seed} resume=rerun reason={reason}", flush=True)
            summary["seeds"] = [
                item
                for item in cast(list[object], summary["seeds"])
                if not isinstance(item, dict) or item.get("seed") != seed
            ]
            _update_summary(summary, spec)
            _write_json_atomic(summary_path, summary)

        seed_dir = output_dir / f"seed-{seed}"

        def report_progress(
            epoch: int, metrics: EvaluationMetrics, _seed: int = seed
        ) -> None:
            print(
                f"{spec.protocol} seed={_seed} epoch={epoch + 1}/{spec.max_epochs} "
                f"validation_accuracy={metrics.accuracy:.6f} "
                f"validation_macro_f1={metrics.macro_f1:.6f}",
                flush=True,
            )

        training_config = TrainingConfig(
            features_root=features_root,
            output_dir=seed_dir,
            max_epochs=spec.max_epochs,
            batch_size=spec.batch_size,
            learning_rate=spec.learning_rate,
            patience=20,
            seed=seed,
            device=device,
            use_class_weights=False,
            num_workers=0,
            deterministic=True,
            model=spec.model_config,
            build_model=spec.build_model,
            needs_feature_stats=spec.needs_feature_stats,
            lr_step=spec.lr_step,
            array_key=spec.array_key,
            array_shape=spec.array_shape,
        )
        _assert_manifest_unchanged(
            manifest_path, manifest_sha256, f"before seed {seed} training", spec
        )
        result = train_model(
            training_config,
            evaluate_test=False,
            progress=report_progress,
        )
        _assert_manifest_unchanged(
            manifest_path, manifest_sha256, f"after seed {seed} training", spec
        )
        metrics_payload = _load_summary(result.metrics_path, spec)
        metrics_payload["experiment"] = {
            "protocol": spec.protocol,
            "seed": seed,
            "feature_manifest_sha256": manifest_sha256,
            "configuration": configuration,
            "configuration_sha256": _canonical_hash(configuration),
        }
        metrics_payload["test_evaluation"] = {
            "status": "deferred",
            "reason": f"Test evaluation is deferred by the {spec.protocol} protocol.",
        }
        metrics_payload.pop("test", None)
        _write_json_atomic(result.metrics_path, metrics_payload)

        model = load_checkpoint(result.checkpoint_path)
        _assert_manifest_unchanged(
            manifest_path, manifest_sha256, f"before seed {seed} ONNX export", spec
        )
        export_metadata = (
            DeploymentMetadata.for_stgcn()
            if spec.schema is None
            else DeploymentMetadata.for_schema(spec.schema)
        )
        exported = export_onnx(model, export_metadata, seed_dir / "onnx")
        _assert_manifest_unchanged(
            manifest_path, manifest_sha256, f"after seed {seed} ONNX export", spec
        )
        expected_paths = _seed_paths(output_dir, seed)
        if exported.model_path != expected_paths["onnx_model"]:
            raise RuntimeError("ONNX exporter returned an unexpected model path")
        if exported.metadata_path != expected_paths["onnx_metadata"]:
            raise RuntimeError("ONNX exporter returned an unexpected metadata path")
        artifacts = _artifact_records(output_dir, expected_paths)
        _assert_manifest_unchanged(
            manifest_path, manifest_sha256, f"before recording seed {seed} completion", spec
        )
        record: dict[str, object] = {
            "seed": seed,
            "status": "complete",
            "feature_manifest_sha256": manifest_sha256,
            "configuration_sha256": _canonical_hash(configuration),
            "best_epoch": result.best_epoch,
            "validation": {
                "accuracy": result.validation.accuracy,
                "macro_f1": result.validation.macro_f1,
            },
            "artifacts": artifacts,
        }
        cast(list[object], summary["seeds"]).append(record)
        _update_summary(summary, spec)
        _write_json_atomic(summary_path, summary)
        print(
            f"{spec.protocol} seed={seed} complete best_epoch={result.best_epoch} "
            f"validation_macro_f1={result.validation.macro_f1:.6f}",
            flush=True,
        )

    _update_summary(summary, spec)
    _write_json_atomic(summary_path, summary)
    completed = tuple(
        int(item["seed"])
        for item in cast(list[dict[str, object]], summary["seeds"])
    )
    return E0ExperimentResult(summary_path, completed)


def reproduce_e0(features_root: Path, output_dir: Path, *, device: str) -> E0ExperimentResult:
    return reproduce_experiment(E0_SPEC, features_root, output_dir, device=device)


__all__ = [
    "E0_SEEDS",
    "E0_SPEC",
    "E0A_SPEC",
    "E0B_SPEC",
    "E0ExperimentResult",
    "E1_SPEC",
    "ExperimentSpec",
    "build_stgcn_model",
    "reproduce_e0",
    "reproduce_experiment",
]
