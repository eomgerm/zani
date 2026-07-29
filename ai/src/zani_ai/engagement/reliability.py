"""Five-seed disagreement analysis for EngageNet label reliability."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import os
import tempfile
from collections.abc import Sequence
from contextlib import suppress
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Literal

import numpy as np
import torch
from numpy.typing import NDArray
from sklearn.metrics import confusion_matrix, f1_score
from torch import nn
from torch.utils.data import DataLoader

from zani_ai.engagement.contracts import LABELS
from zani_ai.engagement.experiment import (
    E0_SPEC,
    _build_configuration,
    _canonical_hash,
    _seed_is_complete,
    _seed_paths,
)
from zani_ai.engagement.training import (
    CachedFeatureDataset,
    _load_feature_datasets,
    load_checkpoint,
    validate_manifest_completion,
)

ReliabilityLabel = Literal["reliable", "ambiguous"]
E0_SEEDS = (42, 43, 44, 45, 46)


@dataclass(frozen=True, slots=True)
class ClipReliability:
    clip_id: str
    split: str
    label: int
    seeds: tuple[int, ...]
    seed_logits: tuple[tuple[float, ...], ...]
    seed_predictions: tuple[int, ...]
    mean_probabilities: tuple[float, ...]
    ensemble_prediction: int
    reliability: ReliabilityLabel
    vote_entropy: float
    max_prediction_distance: int

    @classmethod
    def from_seed_logits(
        cls,
        *,
        clip_id: str,
        split: str,
        label: int,
        seeds: tuple[int, ...],
        seed_logits: tuple[tuple[float, ...], ...],
    ) -> ClipReliability:
        if not clip_id:
            raise ValueError("clip_id must not be empty")
        if len(seeds) != len(seed_logits) or not seeds:
            raise ValueError("seeds and seed_logits must have the same non-zero length")
        logits: NDArray[np.float64] = np.asarray(seed_logits, dtype=np.float64)
        if logits.ndim != 2 or logits.shape[1] != 4:
            raise ValueError("seed_logits must have shape [seed, 4]")
        shifted = logits - logits.max(axis=1, keepdims=True)
        probabilities = np.exp(shifted)
        probabilities /= probabilities.sum(axis=1, keepdims=True)
        predictions = tuple(int(value) for value in logits.argmax(axis=1))
        counts: NDArray[np.float64] = np.bincount(predictions, minlength=4).astype(
            np.float64
        )
        vote_probabilities = counts[counts > 0] / len(predictions)
        vote_entropy = -float(np.sum(vote_probabilities * np.log(vote_probabilities)))
        mean_probabilities = probabilities.mean(axis=0)
        return cls(
            clip_id=clip_id,
            split=split,
            label=label,
            seeds=seeds,
            seed_logits=seed_logits,
            seed_predictions=predictions,
            mean_probabilities=tuple(float(value) for value in mean_probabilities),
            ensemble_prediction=int(mean_probabilities.argmax()),
            reliability="reliable" if len(set(predictions)) == 1 else "ambiguous",
            vote_entropy=vote_entropy,
            max_prediction_distance=max(predictions) - min(predictions),
        )


@dataclass(frozen=True, slots=True)
class ReliabilityCriteria:
    minimum_error_ratio: float = 1.5
    minimum_macro_f1_gain: float = 0.02
    minimum_qwk_gain: float = 0.02


@dataclass(frozen=True, slots=True)
class CriterionResult:
    passed: bool
    required: bool | float
    actual: bool | float


@dataclass(frozen=True, slots=True)
class ReliabilityAssessment:
    decision: Literal["go", "no-go"]
    criteria: dict[str, CriterionResult]
    validation_ambiguous_error_ratio: float
    validation_macro_f1_gain: float
    validation_qwk_gain: float
    validation_full_macro_f1: float
    validation_full_qwk: float
    validation_reliable_macro_f1: float
    validation_reliable_qwk: float


@dataclass(frozen=True, slots=True)
class GroupSummary:
    total_count: int
    ambiguous_count: int
    ambiguous_rate: float


@dataclass(frozen=True, slots=True)
class PredictionErrorSummary:
    clip_count: int
    error_count: int
    error_rate: float
    adjacent_error_count: int
    adjacent_error_share: float


@dataclass(frozen=True, slots=True)
class ReliabilitySummary:
    by_split: dict[str, GroupSummary]
    by_label: dict[int, GroupSummary]
    by_reliability: dict[ReliabilityLabel, PredictionErrorSummary]


@dataclass(frozen=True, slots=True)
class ReliabilityRunResult:
    manifest_path: Path
    csv_path: Path
    report_path: Path
    decision: Literal["go", "no-go"]


@dataclass(frozen=True, slots=True)
class ValidatedReliabilityManifest:
    payload: dict[str, object]
    records: tuple[ClipReliability, ...]
    sha256: str


def _error_rate(records: Sequence[ClipReliability]) -> float:
    if not records:
        return 0.0
    errors = sum(item.ensemble_prediction != item.label for item in records)
    return errors / len(records)


def _group_summary(records: Sequence[ClipReliability]) -> GroupSummary:
    ambiguous_count = sum(item.reliability == "ambiguous" for item in records)
    return GroupSummary(
        total_count=len(records),
        ambiguous_count=ambiguous_count,
        ambiguous_rate=ambiguous_count / len(records) if records else 0.0,
    )


def _error_summary(records: Sequence[ClipReliability]) -> PredictionErrorSummary:
    errors = [item for item in records if item.ensemble_prediction != item.label]
    adjacent_error_count = sum(
        abs(item.ensemble_prediction - item.label) == 1 for item in errors
    )
    return PredictionErrorSummary(
        clip_count=len(records),
        error_count=len(errors),
        error_rate=len(errors) / len(records) if records else 0.0,
        adjacent_error_count=adjacent_error_count,
        adjacent_error_share=adjacent_error_count / len(errors) if errors else 0.0,
    )


def summarize_reliability(records: Sequence[ClipReliability]) -> ReliabilitySummary:
    splits = sorted({item.split for item in records})
    labels = sorted({item.label for item in records})
    by_reliability: dict[ReliabilityLabel, PredictionErrorSummary] = {
        "reliable": _error_summary(
            [item for item in records if item.reliability == "reliable"]
        ),
        "ambiguous": _error_summary(
            [item for item in records if item.reliability == "ambiguous"]
        ),
    }
    return ReliabilitySummary(
        by_split={
            split: _group_summary([item for item in records if item.split == split])
            for split in splits
        },
        by_label={
            label: _group_summary([item for item in records if item.label == label])
            for label in labels
        },
        by_reliability=by_reliability,
    )


def _metrics(records: Sequence[ClipReliability]) -> tuple[float, float]:
    if not records:
        return 0.0, 0.0
    expected = [item.label for item in records]
    predicted = [item.ensemble_prediction for item in records]
    macro_f1 = float(
        f1_score(expected, predicted, labels=list(range(4)), average="macro", zero_division=0)
    )
    confusion = confusion_matrix(expected, predicted, labels=list(range(4)))
    grades = np.arange(4)
    weights = ((grades[:, None] - grades[None, :]) ** 2) / 9
    total = float(confusion.sum())
    expected_confusion = np.outer(confusion.sum(axis=1), confusion.sum(axis=0)) / total
    denominator = float((weights * expected_confusion).sum())
    qwk = 0.0 if denominator == 0.0 else 1.0 - float((weights * confusion).sum()) / denominator
    return macro_f1, qwk


def assess_reliability_signal(
    records: Sequence[ClipReliability],
    *,
    criteria: ReliabilityCriteria | None = None,
) -> ReliabilityAssessment:
    criteria = criteria or ReliabilityCriteria()
    train = [item for item in records if item.split == "train"]
    valid = [item for item in records if item.split == "valid"]
    train_reliable = [item for item in train if item.reliability == "reliable"]
    train_ambiguous = [item for item in train if item.reliability == "ambiguous"]
    valid_reliable = [item for item in valid if item.reliability == "reliable"]
    valid_ambiguous = [item for item in valid if item.reliability == "ambiguous"]

    reliable_error_rate = _error_rate(valid_reliable)
    ambiguous_error_rate = _error_rate(valid_ambiguous)
    if reliable_error_rate == 0.0:
        error_ratio = float("inf") if ambiguous_error_rate > 0.0 else 0.0
    else:
        error_ratio = ambiguous_error_rate / reliable_error_rate

    full_macro_f1, full_qwk = _metrics(valid)
    reliable_macro_f1, reliable_qwk = _metrics(valid_reliable)
    macro_f1_gain = reliable_macro_f1 - full_macro_f1
    qwk_gain = reliable_qwk - full_qwk

    results = {
        "all_labels_have_reliable_train_clips": CriterionResult(
            passed={item.label for item in train_reliable} == set(range(4)),
            required=True,
            actual={item.label for item in train_reliable} == set(range(4)),
        ),
        "train_and_validation_have_ambiguous_clips": CriterionResult(
            passed=bool(train_ambiguous and valid_ambiguous),
            required=True,
            actual=bool(train_ambiguous and valid_ambiguous),
        ),
        "validation_ambiguous_error_ratio": CriterionResult(
            passed=error_ratio >= criteria.minimum_error_ratio,
            required=criteria.minimum_error_ratio,
            actual=error_ratio,
        ),
        "validation_macro_f1_upper_bound_gain": CriterionResult(
            passed=macro_f1_gain >= criteria.minimum_macro_f1_gain,
            required=criteria.minimum_macro_f1_gain,
            actual=macro_f1_gain,
        ),
        "validation_qwk_upper_bound_gain": CriterionResult(
            passed=qwk_gain >= criteria.minimum_qwk_gain,
            required=criteria.minimum_qwk_gain,
            actual=qwk_gain,
        ),
    }
    return ReliabilityAssessment(
        decision="go" if all(item.passed for item in results.values()) else "no-go",
        criteria=results,
        validation_ambiguous_error_ratio=error_ratio,
        validation_macro_f1_gain=macro_f1_gain,
        validation_qwk_gain=qwk_gain,
        validation_full_macro_f1=full_macro_f1,
        validation_full_qwk=full_qwk,
        validation_reliable_macro_f1=reliable_macro_f1,
        validation_reliable_qwk=reliable_qwk,
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _load_json_bytes(path: Path) -> tuple[dict[str, object], str]:
    try:
        raw = path.read_bytes()
        payload = json.loads(raw.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"invalid JSON: {path}") from error
    if not isinstance(payload, dict):
        raise ValueError(f"JSON document must be an object: {path}")
    return payload, hashlib.sha256(raw).hexdigest()


def _finite_float_rows(value: object) -> tuple[tuple[float, ...], ...]:
    if not isinstance(value, list):
        raise ValueError("reliability clip seed_logits must be a list")
    rows: list[tuple[float, ...]] = []
    for row in value:
        if not isinstance(row, list) or len(row) != len(LABELS):
            raise ValueError("reliability clip seed_logits must have shape [5, 4]")
        values: list[float] = []
        for item in row:
            if not isinstance(item, int | float) or isinstance(item, bool):
                raise ValueError("reliability clip logits must be numeric")
            number = float(item)
            if not np.isfinite(number):
                raise ValueError("reliability clip logits must be finite")
            values.append(number)
        rows.append(tuple(values))
    return tuple(rows)


def _clip_from_manifest(item: object) -> ClipReliability:
    if not isinstance(item, dict):
        raise ValueError("reliability manifest contains an invalid clip record")
    clip_id = item.get("clip_id")
    split = item.get("split")
    label = item.get("label")
    seeds = item.get("seeds")
    if (
        not isinstance(clip_id, str)
        or not clip_id
        or split not in ("train", "valid")
        or not isinstance(label, int)
        or isinstance(label, bool)
        or label not in range(len(LABELS))
        or seeds != list(E0_SEEDS)
    ):
        raise ValueError("reliability manifest contains an invalid clip identity")
    reconstructed = ClipReliability.from_seed_logits(
        clip_id=clip_id,
        split=split,
        label=label,
        seeds=E0_SEEDS,
        seed_logits=_finite_float_rows(item.get("seed_logits")),
    )
    exact_fields = (
        "seed_predictions",
        "ensemble_prediction",
        "reliability",
        "max_prediction_distance",
    )
    expected = asdict(reconstructed)
    for field in exact_fields:
        actual = item.get(field)
        normalized_expected = (
            list(expected[field]) if field == "seed_predictions" else expected[field]
        )
        if actual != normalized_expected:
            raise ValueError(f"reliability clip {field} does not match seed logits")
    probabilities = item.get("mean_probabilities")
    if (
        not isinstance(probabilities, list)
        or len(probabilities) != len(LABELS)
        or not np.allclose(
            np.asarray(probabilities, dtype=np.float64),
            np.asarray(reconstructed.mean_probabilities),
            rtol=0.0,
            atol=1e-12,
        )
    ):
        raise ValueError("reliability clip mean_probabilities do not match seed logits")
    vote_entropy = item.get("vote_entropy")
    if (
        not isinstance(vote_entropy, int | float)
        or isinstance(vote_entropy, bool)
        or not math.isclose(float(vote_entropy), reconstructed.vote_entropy, abs_tol=1e-12)
    ):
        raise ValueError("reliability clip vote_entropy does not match seed logits")
    return reconstructed


def load_validated_reliability_manifest(
    path: Path,
    *,
    feature_manifest_sha256: str,
    require_go: bool,
) -> ValidatedReliabilityManifest:
    payload, digest = _load_json_bytes(path)
    if payload.get("schema_version") != "label_reliability_v1":
        raise ValueError("reliability manifest schema must be label_reliability_v1")
    inputs = payload.get("inputs")
    feature_record = inputs.get("feature_manifest") if isinstance(inputs, dict) else None
    if (
        not isinstance(feature_record, dict)
        or feature_record.get("sha256") != feature_manifest_sha256
    ):
        raise ValueError("reliability and feature manifest fingerprints differ")
    clips = payload.get("clips")
    if not isinstance(clips, list):
        raise ValueError("reliability manifest has no clip records")
    records = tuple(_clip_from_manifest(item) for item in clips)
    identities = {(item.split, item.clip_id) for item in records}
    if len(identities) != len(records):
        raise ValueError("reliability manifest contains duplicate clip records")

    criteria = ReliabilityCriteria()
    expected_criteria = _json_safe(asdict(criteria))
    if payload.get("criteria") != expected_criteria:
        raise ValueError("reliability manifest criteria are not the fixed production criteria")
    summary = summarize_reliability(records)
    if payload.get("summary") != _json_safe(asdict(summary)):
        raise ValueError("reliability manifest summary does not match clip records")
    assessment = assess_reliability_signal(records, criteria=criteria)
    if payload.get("assessment") != _json_safe(asdict(assessment)):
        raise ValueError("reliability manifest assessment does not match clip records")
    if payload.get("decision") != assessment.decision:
        raise ValueError("reliability manifest decision does not match recomputed assessment")
    if require_go and assessment.decision != "go":
        raise ValueError("reliability manifest decision is not go")
    return ValidatedReliabilityManifest(payload, records, digest)


def _baseline_checkpoints(
    baseline_output: Path,
    *,
    feature_manifest_sha256: str,
) -> tuple[dict[str, object], str, list[tuple[int, Path, str, int]]]:
    summary_path = baseline_output / "summary.json"
    summary, summary_sha256 = _load_json_bytes(summary_path)
    if summary.get("protocol") != "E0" or summary.get("status") != "complete":
        raise ValueError("baseline must be a complete E0 five-seed run")
    manifest_record = summary.get("feature_manifest")
    if (
        not isinstance(manifest_record, dict)
        or manifest_record.get("sha256") != feature_manifest_sha256
    ):
        raise ValueError("baseline and feature manifest fingerprints differ")
    seed_records = summary.get("seeds")
    if not isinstance(seed_records, list):
        raise ValueError("baseline summary has no seed records")
    by_seed = {
        item.get("seed"): item
        for item in seed_records
        if isinstance(item, dict) and isinstance(item.get("seed"), int)
    }
    if set(by_seed) != set(E0_SEEDS):
        raise ValueError("baseline must contain exactly seeds 42,43,44,45,46")

    configuration = summary.get("configuration")
    recorded_device = configuration.get("device") if isinstance(configuration, dict) else None
    if not isinstance(recorded_device, str):
        raise ValueError("baseline has no canonical E0 configuration")
    canonical_configuration = _build_configuration(E0_SPEC, recorded_device)
    if configuration != canonical_configuration:
        raise ValueError("baseline does not use the canonical E0 configuration")
    if summary.get("configuration_sha256") != _canonical_hash(canonical_configuration):
        raise ValueError("baseline has an invalid E0 configuration fingerprint")

    root = baseline_output.resolve()
    checkpoints: list[tuple[int, Path, str, int]] = []
    for seed in E0_SEEDS:
        record = by_seed[seed]
        complete, reason = _seed_is_complete(
            record,
            seed=seed,
            output_dir=baseline_output,
            manifest_sha256=feature_manifest_sha256,
            configuration=canonical_configuration,
            inputs={},
            spec=E0_SPEC,
        )
        if not complete:
            raise ValueError(f"baseline seed {seed} is not complete: {reason}")
        artifacts = record.get("artifacts")
        checkpoint_record = artifacts.get("checkpoint") if isinstance(artifacts, dict) else None
        if not isinstance(checkpoint_record, dict):
            raise ValueError(f"baseline seed {seed} checkpoint integrity record is missing")
        relative = checkpoint_record.get("path")
        expected_hash = checkpoint_record.get("sha256")
        expected_size = checkpoint_record.get("size_bytes")
        if (
            not isinstance(relative, str)
            or not isinstance(expected_hash, str)
            or not isinstance(expected_size, int)
            or isinstance(expected_size, bool)
            or expected_size <= 0
        ):
            raise ValueError(f"baseline seed {seed} checkpoint integrity record is invalid")
        checkpoint = _seed_paths(baseline_output, seed)["checkpoint"].resolve()
        if not checkpoint.is_relative_to(root):
            raise ValueError(f"baseline seed {seed} checkpoint path escapes its output directory")
        if (
            not checkpoint.is_file()
            or checkpoint.stat().st_size != expected_size
            or _sha256(checkpoint) != expected_hash
        ):
            raise ValueError(f"baseline seed {seed} checkpoint integrity check failed")
        checkpoints.append((seed, checkpoint, expected_hash, expected_size))
    if len({path for _, path, _, _ in checkpoints}) != len(E0_SEEDS):
        raise ValueError("baseline seed checkpoints must be unique")
    return summary, summary_sha256, checkpoints


def _infer_logits(
    model: nn.Module,
    dataset: CachedFeatureDataset,
    *,
    device: str,
) -> list[tuple[float, ...]]:
    model.eval()
    results: list[tuple[float, ...]] = []
    loader = DataLoader(dataset, batch_size=64, shuffle=False)
    with torch.inference_mode():
        for tokens, _ in loader:
            batch = model(tokens.to(device)).detach().cpu().tolist()
            results.extend(tuple(float(value) for value in row) for row in batch)
    if len(results) != len(dataset.entries):
        raise RuntimeError("inference output count does not match feature entries")
    return results


def _json_safe(value: object) -> object:
    if isinstance(value, float) and not np.isfinite(value):
        return "infinity" if value > 0 else "-infinity"
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    if isinstance(value, list | tuple):
        return [_json_safe(item) for item in value]
    return value


def _write_text_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as target:
            target.write(content)
        os.replace(temporary_name, path)
    except BaseException:
        with suppress(FileNotFoundError):
            os.unlink(temporary_name)
        raise


def _csv_content(records: Sequence[ClipReliability]) -> str:
    output = io.StringIO(newline="")
    fieldnames = [
        "clip_id",
        "split",
        "label",
        "reliability",
        "ensemble_prediction",
        "vote_entropy",
        "max_prediction_distance",
        *(f"seed_{seed}_prediction" for seed in E0_SEEDS),
    ]
    writer = csv.DictWriter(output, fieldnames=fieldnames)
    writer.writeheader()
    for record in records:
        row: dict[str, object] = {
            "clip_id": record.clip_id,
            "split": record.split,
            "label": record.label,
            "reliability": record.reliability,
            "ensemble_prediction": record.ensemble_prediction,
            "vote_entropy": record.vote_entropy,
            "max_prediction_distance": record.max_prediction_distance,
        }
        row.update(
            {
                f"seed_{seed}_prediction": prediction
                for seed, prediction in zip(record.seeds, record.seed_predictions, strict=True)
            }
        )
        writer.writerow(row)
    return output.getvalue()


def _report_content(
    records: Sequence[ClipReliability],
    summary: ReliabilitySummary,
    assessment: ReliabilityAssessment,
) -> str:
    lines = [
        "# Label reliability analysis",
        "",
        f"Decision: **{assessment.decision}**",
        "",
        "## Disagreement distribution",
        "",
        "| Split | Clips | Ambiguous | Rate |",
        "| --- | ---: | ---: | ---: |",
    ]
    for split, item in summary.by_split.items():
        lines.append(
            f"| {split} | {item.total_count} | {item.ambiguous_count} | "
            f"{item.ambiguous_rate:.2%} |"
        )
    lines.extend(
        [
            "",
            "## Label distribution",
            "",
            "| Label | Clips | Ambiguous | Rate |",
            "| --- | ---: | ---: | ---: |",
        ]
    )
    for label, item in summary.by_label.items():
        lines.append(
            f"| {LABELS[label]} | {item.total_count} | {item.ambiguous_count} | "
            f"{item.ambiguous_rate:.2%} |"
        )
    lines.extend(
        [
            "",
            "## Error concentration and adjacency",
            "",
            "| Reliability | Clips | Errors | Error rate | Adjacent errors | "
            "Adjacent error share |",
            "| --- | ---: | ---: | ---: | ---: | ---: |",
        ]
    )
    for reliability, error_summary in summary.by_reliability.items():
        lines.append(
            f"| {reliability} | {error_summary.clip_count} | "
            f"{error_summary.error_count} | {error_summary.error_rate:.2%} | "
            f"{error_summary.adjacent_error_count} | "
            f"{error_summary.adjacent_error_share:.2%} |"
        )
    lines.extend(
        [
            "",
            "## Validation exclusion upper bound",
            "",
            "| Evaluation set | Macro-F1 | QWK |",
            "| --- | ---: | ---: |",
            f"| Full Validation | {assessment.validation_full_macro_f1:.6f} | "
            f"{assessment.validation_full_qwk:.6f} |",
            f"| Reliable-only Validation | "
            f"{assessment.validation_reliable_macro_f1:.6f} | "
            f"{assessment.validation_reliable_qwk:.6f} |",
            f"| Exclusion gain | {assessment.validation_macro_f1_gain:+.6f} | "
            f"{assessment.validation_qwk_gain:+.6f} |",
            "",
            "## Go/no-go criteria",
            "",
            "| Criterion | Required | Actual | Pass |",
            "| --- | ---: | ---: | :---: |",
        ]
    )
    for name, criterion in assessment.criteria.items():
        passed = "yes" if criterion.passed else "no"
        lines.append(
            f"| {name} | {criterion.required} | {criterion.actual} | {passed} |"
        )
    lines.extend(
        [
            "",
            "## VLM Accepted/Rejected comparison",
            "",
            "This signal measures instability among five task models. It can identify hard clips, "
            "but it cannot fully separate ambiguous human labels from model weakness. The cited "
            "VLM Accepted/Rejected split uses an external semantic evaluator, so its evidence "
            "source is different. This report therefore treats disagreement as a low-cost proxy, "
            "not ground truth.",
            "",
            f"Analyzed clips: {len(records)} (Train and Validation only; Test excluded).",
            "",
        ]
    )
    return "\n".join(lines)


def analyze_label_reliability(
    features_root: Path,
    baseline_output: Path,
    output_dir: Path,
    *,
    device: str = "cuda" if torch.cuda.is_available() else "cpu",
    criteria: ReliabilityCriteria | None = None,
) -> ReliabilityRunResult:
    criteria = criteria or ReliabilityCriteria()
    manifest_path = features_root / "manifest.json"
    manifest, manifest_sha256 = _load_json_bytes(manifest_path)
    validate_manifest_completion(manifest)
    schema = manifest.get("schema")
    if not isinstance(schema, str):
        raise ValueError("feature manifest is missing its schema")
    _, baseline_summary_sha256, checkpoints = _baseline_checkpoints(
        baseline_output, feature_manifest_sha256=manifest_sha256
    )

    def assert_inputs_unchanged(boundary: str) -> None:
        expected_files = [
            (manifest_path, manifest_sha256, None, "feature manifest"),
            (
                baseline_output / "summary.json",
                baseline_summary_sha256,
                None,
                "baseline summary",
            ),
            *[
                (path, digest, size, f"baseline seed {seed} checkpoint")
                for seed, path, digest, size in checkpoints
            ],
        ]
        for path, expected_digest, expected_size, name in expected_files:
            try:
                if expected_size is not None and path.stat().st_size != expected_size:
                    raise RuntimeError(f"{name} changed {boundary}")
                actual_digest = _sha256(path)
            except OSError as error:
                raise RuntimeError(f"{name} unavailable {boundary}: {path}") from error
            if actual_digest != expected_digest:
                raise RuntimeError(f"{name} changed {boundary}")

    assert_inputs_unchanged("before inference")
    datasets = _load_feature_datasets(
        features_root,
        include_test=False,
        expected_schema=schema,
    )
    split_datasets = {"train": datasets.train, "valid": datasets.valid}
    logits_by_split: dict[str, list[list[tuple[float, ...]]]] = {
        split: [[] for _ in dataset.entries] for split, dataset in split_datasets.items()
    }
    for seed, checkpoint, _, _ in checkpoints:
        assert_inputs_unchanged(f"before seed {seed} inference")
        model = load_checkpoint(checkpoint, device)
        for split, dataset in split_datasets.items():
            logits = _infer_logits(model, dataset, device=device)
            for index, row in enumerate(logits):
                logits_by_split[split][index].append(row)
        assert_inputs_unchanged(f"after seed {seed} inference")

    records: list[ClipReliability] = []
    for split, dataset in split_datasets.items():
        for index, entry in enumerate(dataset.entries):
            records.append(
                ClipReliability.from_seed_logits(
                    clip_id=entry.clip_id,
                    split=split,
                    label=entry.label_index,
                    seeds=E0_SEEDS,
                    seed_logits=tuple(logits_by_split[split][index]),
                )
            )
    summary = summarize_reliability(records)
    assessment = assess_reliability_signal(records, criteria=criteria)
    payload = {
        "schema_version": "label_reliability_v1",
        "decision": assessment.decision,
        "inputs": {
            "feature_manifest": {
                "path": str(manifest_path.resolve()),
                "sha256": manifest_sha256,
            },
            "baseline_summary": {
                "path": str((baseline_output / "summary.json").resolve()),
                "sha256": baseline_summary_sha256,
            },
            "checkpoints": [
                {"seed": seed, "path": str(path), "sha256": digest}
                for seed, path, digest, _ in checkpoints
            ],
        },
        "criteria": asdict(criteria),
        "assessment": asdict(assessment),
        "summary": asdict(summary),
        "clips": [asdict(record) for record in records],
    }
    manifest_output = output_dir / "reliability_manifest.json"
    csv_output = output_dir / "clips.csv"
    report_output = output_dir / "report.md"
    assert_inputs_unchanged("before artifact publication")
    _write_text_atomic(
        manifest_output,
        json.dumps(_json_safe(payload), ensure_ascii=False, indent=2, allow_nan=False) + "\n",
    )
    _write_text_atomic(csv_output, _csv_content(records))
    _write_text_atomic(report_output, _report_content(records, summary, assessment))
    return ReliabilityRunResult(
        manifest_path=manifest_output,
        csv_path=csv_output,
        report_path=report_output,
        decision=assessment.decision,
    )


__all__ = [
    "ClipReliability",
    "CriterionResult",
    "GroupSummary",
    "PredictionErrorSummary",
    "ReliabilityAssessment",
    "ReliabilityCriteria",
    "ReliabilityRunResult",
    "ReliabilitySummary",
    "ValidatedReliabilityManifest",
    "analyze_label_reliability",
    "assess_reliability_signal",
    "load_validated_reliability_manifest",
    "summarize_reliability",
]
