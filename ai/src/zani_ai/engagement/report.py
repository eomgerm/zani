from __future__ import annotations

import hashlib
import json
import statistics
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast

import numpy as np
import torch

from zani_ai.engagement.contracts import LABELS
from zani_ai.engagement.experiment import E0_SPEC, ExperimentSpec
from zani_ai.engagement.runtime import parse_device
from zani_ai.engagement.training import (
    TrainingConfig,
    _load_feature_datasets,
    _loader,
    evaluate_model,
    load_checkpoint,
    validate_manifest_completion,
)

RESULTS_FILENAME = "test_results.json"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_json(path: Path) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON: {path}") from error
    if not isinstance(payload, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return cast(dict[str, Any], payload)


def _write_text_atomic(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    try:
        temporary.write_text(text, encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def _write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    _write_text_atomic(path, json.dumps(payload, ensure_ascii=False, indent=2) + "\n")


def _summary_seed(summary: dict[str, Any], seed: int) -> dict[str, Any]:
    for item in summary.get("seeds", []):
        if isinstance(item, dict) and item.get("seed") == seed:
            return cast(dict[str, Any], item)
    raise ValueError(f"E0 summary is missing seed {seed}")


def _checkpoint(output_dir: Path, record: dict[str, Any]) -> tuple[Path, str]:
    artifact = record.get("artifacts", {}).get("checkpoint")
    if not isinstance(artifact, dict):
        raise ValueError("checkpoint integrity record is missing")
    relative = artifact.get("path")
    expected_hash = artifact.get("sha256")
    expected_size = artifact.get("size_bytes")
    if not isinstance(relative, str) or not isinstance(expected_hash, str):
        raise ValueError("checkpoint integrity record is invalid")
    path = output_dir / relative
    if not path.is_file() or path.stat().st_size != expected_size:
        raise ValueError(f"checkpoint is missing or changed: {path}")
    if _sha256(path) != expected_hash:
        raise ValueError(f"checkpoint hash mismatch: {path}")
    return path, expected_hash


def _summarize(values: list[float]) -> dict[str, float]:
    return {
        "mean": statistics.fmean(values),
        "sample_standard_deviation": statistics.stdev(values),
    }


def _aggregate(records: list[dict[str, Any]]) -> dict[str, Any]:
    accuracies = [float(item["test"]["accuracy"]) for item in records]
    macro_f1s = [float(item["test"]["macro_f1"]) for item in records]
    pooled = np.sum(
        [np.asarray(item["test"]["confusion_matrix"], dtype=np.int64) for item in records],
        axis=0,
    )
    per_class: dict[str, Any] = {}
    for label in LABELS:
        reports = [
            cast(dict[str, Any], item["test"]["classification_report"])[label] for item in records
        ]
        per_class[label] = {
            metric: _summarize([float(report[metric]) for report in reports])
            for metric in ("precision", "recall", "f1-score")
        }
        per_class[label]["support_per_seed"] = int(reports[0]["support"])
    result: dict[str, Any] = {
        "completed_seed_count": len(records),
        "test_accuracy": _summarize(accuracies),
        "test_macro_f1": _summarize(macro_f1s),
        "pooled_confusion_matrix": pooled.tolist(),
        "per_class": per_class,
    }
    # `metrics.to_dict()` picks the new fields up automatically, so finalize
    # output always carries them. Aggregating only when every seed has them
    # rules out mixing with a test_results written before they existed.
    if all("within_one_accuracy" in item["test"] for item in records):
        result["test_within_one_accuracy"] = _summarize(
            [float(item["test"]["within_one_accuracy"]) for item in records]
        )
        result["test_quadratic_weighted_kappa"] = _summarize(
            [float(item["test"]["quadratic_weighted_kappa"]) for item in records]
        )
    return result


def evaluate_frozen_checkpoints(
    features_root: Path, output_dir: Path, *, device: str, spec: ExperimentSpec
) -> Path:
    """Evaluate each validation-selected checkpoint on Test exactly once.

    Results are committed atomically after every seed. A valid recorded seed is never
    evaluated again, which keeps Test strictly post-selection even after interruption.
    """

    protocol = f"{spec.protocol}-fixed-checkpoint-test"
    try:
        kind, _ = parse_device(device)
    except ValueError as error:
        raise ValueError(f"{spec.protocol} evaluation {error}") from error
    if kind == "cuda" and not torch.cuda.is_available():
        raise RuntimeError(
            f"{spec.protocol} Test evaluation requested CUDA, but CUDA is unavailable"
        )
    summary_path = output_dir / "summary.json"
    manifest_path = features_root / "manifest.json"
    summary = _load_json(summary_path)
    manifest = _load_json(manifest_path)
    validate_manifest_completion(manifest)
    if summary.get("status") != "complete":
        raise ValueError(
            f"five-seed {spec.protocol} training must be complete before Test evaluation"
        )
    manifest_hash = _sha256(manifest_path)
    summary_manifest = summary.get("feature_manifest", {})
    if summary_manifest.get("sha256") != manifest_hash:
        raise ValueError(f"{spec.protocol} summary and feature manifest fingerprints differ")

    results_path = output_dir / RESULTS_FILENAME
    if results_path.is_file():
        results = _load_json(results_path)
        if (
            results.get("protocol") != protocol
            or results.get("feature_manifest_sha256") != manifest_hash
            or results.get("configuration_sha256") != summary.get("configuration_sha256")
        ):
            raise ValueError(f"existing Test results belong to a different {spec.protocol} run")
    else:
        results = {
            "protocol": protocol,
            "status": "in_progress",
            "selection_policy": (
                "Validation-only; Test is evaluated once after all checkpoints are frozen."
            ),
            "feature_manifest_sha256": manifest_hash,
            "configuration_sha256": summary.get("configuration_sha256"),
            "seeds": [],
        }
        _write_json_atomic(results_path, results)

    recorded = {
        int(item["seed"]): item
        for item in results.get("seeds", [])
        if isinstance(item, dict) and item.get("seed") in spec.seeds
    }
    datasets = None
    for seed in spec.seeds:
        summary_record = _summary_seed(summary, seed)
        checkpoint_path, checkpoint_hash = _checkpoint(output_dir, summary_record)
        existing = recorded.get(seed)
        if existing is not None:
            if existing.get("checkpoint_sha256") != checkpoint_hash:
                raise ValueError(f"recorded Test result checkpoint mismatch for seed {seed}")
            print(f"{spec.protocol} Test seed={seed} resume=complete", flush=True)
            continue
        if datasets is None:
            datasets = _load_feature_datasets(
                features_root,
                include_test=True,
                expected_schema=spec.schema_name,
                array_key=spec.array_key,
                array_shape=spec.array_shape,
            )
        if datasets.test is None:
            raise RuntimeError("Test feature split is unavailable")
        config = TrainingConfig(
            features_root=features_root,
            output_dir=output_dir / f"seed-{seed}",
            batch_size=32,
            seed=seed,
            device=device,
            num_workers=0,
            deterministic=True,
            array_key=spec.array_key,
            array_shape=spec.array_shape,
        )
        model = load_checkpoint(checkpoint_path, device)
        metrics = evaluate_model(
            model,
            _loader(datasets.test, config, shuffle=False),
            torch.device(device),
        )
        record = {
            "seed": seed,
            "checkpoint_path": checkpoint_path.relative_to(output_dir).as_posix(),
            "checkpoint_sha256": checkpoint_hash,
            "validation": summary_record["validation"],
            "test": metrics.to_dict(),
        }
        cast(list[dict[str, Any]], results["seeds"]).append(record)
        results["seeds"] = sorted(results["seeds"], key=lambda item: item["seed"])
        _write_json_atomic(results_path, results)
        print(
            f"{spec.protocol} Test seed={seed} complete accuracy={metrics.accuracy:.6f} "
            f"macro_f1={metrics.macro_f1:.6f}",
            flush=True,
        )

    records = cast(list[dict[str, Any]], results["seeds"])
    if [item["seed"] for item in records] != list(spec.seeds):
        raise RuntimeError(f"not all {spec.protocol} seeds have Test results")
    results["aggregate"] = _aggregate(records)
    results["status"] = "complete"
    results["completed_at_utc"] = datetime.now(UTC).isoformat()
    _write_json_atomic(results_path, results)
    summary["test_evaluation"] = {
        "status": "complete",
        "policy": "Validation-only selection, then one frozen-checkpoint Test pass per seed.",
        "results_path": results_path.relative_to(output_dir).as_posix(),
        "results_sha256": _sha256(results_path),
        "aggregate": results["aggregate"],
    }
    _write_json_atomic(summary_path, summary)
    return results_path


def evaluate_frozen_e0_checkpoints(features_root: Path, output_dir: Path, *, device: str) -> Path:
    """Backward-compatible E0 wrapper; identical output to before generalization."""

    return evaluate_frozen_checkpoints(features_root, output_dir, device=device, spec=E0_SPEC)


def finalize_experiment(
    spec: ExperimentSpec,
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
) -> Path:
    """Evaluate frozen checkpoints and return the JSON results path."""

    return evaluate_frozen_checkpoints(
        features_root,
        output_dir,
        device=device,
        spec=spec,
    )


def finalize_e0(
    features_root: Path,
    output_dir: Path,
    *,
    device: str,
) -> Path:
    """Backward-compatible E0 finalization wrapper."""

    return finalize_experiment(
        E0_SPEC,
        features_root,
        output_dir,
        device=device,
    )


__all__ = [
    "E0_SPEC",
    "evaluate_frozen_checkpoints",
    "evaluate_frozen_e0_checkpoints",
    "finalize_e0",
    "finalize_experiment",
]
