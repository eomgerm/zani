from __future__ import annotations

import hashlib
import json
import warnings
from pathlib import Path

import numpy as np
import pytest
import torch

from zani_ai.engagement import reliability as reliability_module
from zani_ai.engagement.experiment import E0_SPEC, _build_configuration, _canonical_hash
from zani_ai.engagement.model import EngagementTransformer, ModelConfig
from zani_ai.engagement.reliability import (
    ClipReliability,
    ReliabilityCriteria,
    analyze_label_reliability,
    assess_reliability_signal,
    load_validated_reliability_manifest,
    summarize_reliability,
)


def _logits(prediction: int) -> tuple[float, ...]:
    values = [-4.0, -4.0, -4.0, -4.0]
    values[prediction] = 4.0
    return tuple(values)


def _record(
    clip_id: str,
    split: str,
    label: int,
    predictions: tuple[int, ...],
) -> ClipReliability:
    return ClipReliability.from_seed_logits(
        clip_id=clip_id,
        split=split,
        label=label,
        seeds=(42, 43, 44, 45, 46),
        seed_logits=tuple(_logits(prediction) for prediction in predictions),
    )


def test_unanimous_seed_predictions_mark_a_clip_reliable() -> None:
    record = _record("clip-1", "train", 2, (2, 2, 2, 2, 2))

    assert record.reliability == "reliable"
    assert record.seed_predictions == (2, 2, 2, 2, 2)
    assert record.ensemble_prediction == 2
    assert record.vote_entropy == 0.0
    assert record.max_prediction_distance == 0


def test_any_seed_disagreement_marks_a_clip_ambiguous() -> None:
    record = _record("clip-2", "valid", 2, (1, 2, 2, 3, 2))

    assert record.reliability == "ambiguous"
    assert record.ensemble_prediction == 2
    assert record.vote_entropy > 0.0
    assert record.max_prediction_distance == 2


def test_signal_is_go_when_disagreement_concentrates_validation_errors() -> None:
    train = [
        _record(f"train-{label}", "train", label, (label,) * 5)
        for label in range(4)
    ]
    train.append(_record("train-ambiguous", "train", 1, (0, 1, 1, 2, 1)))

    valid: list[ClipReliability] = []
    for label in range(4):
        valid.append(_record(f"valid-{label}-a", "valid", label, (label,) * 5))
        valid.append(_record(f"valid-{label}-b", "valid", label, (label,) * 5))
    # Keep one reliable error so the approved 1.5x ratio has a finite value.
    valid[0] = _record("valid-0-a", "valid", 0, (1,) * 5)
    for label in range(4):
        wrong = (label + 1) % 4
        predictions = (wrong, wrong, label, wrong, wrong)
        valid.append(_record(f"valid-ambiguous-{label}", "valid", label, predictions))

    assessment = assess_reliability_signal(
        [*train, *valid],
        criteria=ReliabilityCriteria(
            minimum_error_ratio=1.5,
            minimum_macro_f1_gain=0.02,
            minimum_qwk_gain=0.02,
        ),
    )

    assert assessment.decision == "go"
    assert all(item.passed for item in assessment.criteria.values())
    assert assessment.validation_ambiguous_error_ratio >= 1.5
    assert assessment.validation_macro_f1_gain >= 0.02
    assert assessment.validation_qwk_gain >= 0.02


def test_signal_is_no_go_when_a_train_label_has_no_reliable_clip() -> None:
    records = [
        _record(f"train-{label}", "train", label, (label,) * 5)
        for label in range(3)
    ]
    records.extend(
        [
            _record("train-ambiguous", "train", 3, (2, 3, 3, 2, 3)),
            _record("valid-reliable", "valid", 0, (0,) * 5),
            _record("valid-ambiguous", "valid", 0, (0, 1, 1, 1, 1)),
        ]
    )

    assessment = assess_reliability_signal(records)

    assert assessment.decision == "no-go"
    assert assessment.criteria["all_labels_have_reliable_train_clips"].passed is False


def test_degenerate_validation_subset_does_not_emit_a_kappa_warning() -> None:
    records = [
        _record("train-reliable", "train", 0, (0,) * 5),
        _record("train-ambiguous", "train", 0, (0, 1, 0, 1, 0)),
        _record("valid-reliable", "valid", 0, (0,) * 5),
        _record("valid-ambiguous", "valid", 0, (0, 1, 0, 1, 0)),
    ]

    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter("always")
        assessment = assess_reliability_signal(records)

    assert assessment.validation_qwk_gain == 0.0
    assert caught == []


def test_summary_breaks_disagreement_down_by_label_and_adjacent_error() -> None:
    records = [
        _record("reliable-0", "valid", 0, (0,) * 5),
        _record("ambiguous-0", "valid", 0, (1, 1, 0, 1, 1)),
        _record("ambiguous-1", "valid", 1, (3, 3, 1, 3, 3)),
    ]

    summary = summarize_reliability(records)

    assert summary.by_label[0].total_count == 2
    assert summary.by_label[0].ambiguous_count == 1
    assert summary.by_label[0].ambiguous_rate == 0.5
    assert summary.by_label[1].ambiguous_rate == 1.0
    assert summary.by_reliability["ambiguous"].error_count == 2
    assert summary.by_reliability["ambiguous"].adjacent_error_count == 1
    assert summary.by_reliability["ambiguous"].adjacent_error_share == 0.5


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_analysis_fixture(root: Path) -> tuple[Path, Path]:
    features = root / "features"
    included: list[dict[str, object]] = []
    for split in ("train", "valid", "test"):
        for label in range(4):
            path = features / "mediapipe_98_v1" / split / f"{split}-{label}.npz"
            path.parent.mkdir(parents=True, exist_ok=True)
            np.savez_compressed(path, tokens=np.full((20, 98), label, dtype=np.float32))
            included.append(
                {
                    "clip_id": f"{split}-{label}",
                    "split": split,
                    "label_index": label,
                    "feature_path": path.relative_to(features).as_posix(),
                }
            )
    manifest_path = features / "manifest.json"
    manifest_path.write_text(
        json.dumps(
            {
                "schema": "mediapipe_98_v1",
                "status": "complete",
                "complete": True,
                "processed_count": len(included),
                "total_count": len(included),
                "included": included,
                "excluded": [],
            }
        ),
        encoding="utf-8",
    )

    baseline = root / "e0"
    seed_records: list[dict[str, object]] = []
    experiment_configuration = _build_configuration(E0_SPEC, "cpu")
    configuration_sha256 = _canonical_hash(experiment_configuration)
    config = ModelConfig(d_model=8, nhead=2, num_layers=1, mlp_dim=8, dropout=0.0)
    for index, seed in enumerate((42, 43, 44, 45, 46)):
        model = EngagementTransformer(torch.zeros(98), torch.ones(98), config=config)
        for parameter in model.parameters():
            parameter.data.zero_()
        model.classifier[3].bias.data[index % 4] = 4.0
        checkpoint = baseline / f"seed-{seed}" / "best.pt"
        checkpoint.parent.mkdir(parents=True, exist_ok=True)
        torch.save(
            {
                "model_state": model.state_dict(),
                "model_config": config.to_dict(),
                "feature_mean": np.zeros(98, dtype=np.float32),
                "feature_std": np.ones(98, dtype=np.float32),
                "epoch": 0,
                "schema": "mediapipe_98_v1",
                "labels": ["Not-Engaged", "Barely-Engaged", "Engaged", "Highly-Engaged"],
                "model_family": "transformer",
            },
            checkpoint,
        )
        metrics = baseline / f"seed-{seed}" / "metrics.json"
        metrics.write_text(
            json.dumps(
                {
                    "best_epoch": 0,
                    "validation": {"accuracy": 0.25, "macro_f1": 0.1},
                    "test_evaluation": {"status": "deferred"},
                    "experiment": {
                        "protocol": "E0",
                        "seed": seed,
                        "feature_manifest_sha256": _sha256(manifest_path),
                        "configuration": experiment_configuration,
                        "configuration_sha256": configuration_sha256,
                    },
                }
            ),
            encoding="utf-8",
        )
        onnx_model = baseline / f"seed-{seed}" / "onnx" / "engagement.onnx"
        onnx_model.parent.mkdir(parents=True, exist_ok=True)
        onnx_model.write_bytes(b"synthetic-onnx")
        onnx_metadata = onnx_model.with_name("engagement.metadata.json")
        onnx_metadata.write_text("{}", encoding="utf-8")
        artifacts = {}
        for name, path in {
            "checkpoint": checkpoint,
            "metrics": metrics,
            "onnx_model": onnx_model,
            "onnx_metadata": onnx_metadata,
        }.items():
            artifacts[name] = {
                "path": path.relative_to(baseline).as_posix(),
                "sha256": _sha256(path),
                "size_bytes": path.stat().st_size,
            }
        seed_records.append(
            {
                "seed": seed,
                "status": "complete",
                "feature_manifest_sha256": _sha256(manifest_path),
                "configuration_sha256": configuration_sha256,
                "best_epoch": 0,
                "validation": {"accuracy": 0.25, "macro_f1": 0.1},
                "artifacts": artifacts,
            }
        )
    (baseline / "summary.json").write_text(
        json.dumps(
            {
                "protocol": "E0",
                "status": "complete",
                "feature_manifest": {"sha256": _sha256(manifest_path)},
                "configuration": experiment_configuration,
                "configuration_sha256": configuration_sha256,
                "seeds": seed_records,
            }
        ),
        encoding="utf-8",
    )
    return features, baseline


def test_analysis_materializes_train_and_validation_without_touching_test(
    tmp_path: Path,
) -> None:
    features, baseline = _write_analysis_fixture(tmp_path)

    result = analyze_label_reliability(
        features,
        baseline,
        tmp_path / "analysis",
        device="cpu",
    )

    payload = json.loads(result.manifest_path.read_text(encoding="utf-8"))
    assert payload["schema_version"] == "label_reliability_v1"
    assert payload["decision"] == "no-go"
    assert len(payload["clips"]) == 8
    assert {item["split"] for item in payload["clips"]} == {"train", "valid"}
    assert payload["clips"][0]["seed_predictions"] == [0, 1, 2, 3, 0]
    assert result.csv_path.is_file()
    assert result.report_path.is_file()
    report = result.report_path.read_text(encoding="utf-8")
    assert "VLM Accepted/Rejected" in report
    assert "Adjacent error share" in report
    assert "Full Validation" in report
    assert "Reliable-only Validation" in report


def test_reliability_manifest_rejects_a_forged_go_decision(tmp_path: Path) -> None:
    features, baseline = _write_analysis_fixture(tmp_path)
    result = analyze_label_reliability(features, baseline, tmp_path / "analysis", device="cpu")
    payload = json.loads(result.manifest_path.read_text(encoding="utf-8"))
    payload["decision"] = "go"
    forged = tmp_path / "forged.json"
    forged.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValueError, match="decision does not match"):
        load_validated_reliability_manifest(
            forged,
            feature_manifest_sha256=_sha256(features / "manifest.json"),
            require_go=True,
        )


def test_reliability_manifest_rejects_a_classification_inconsistent_with_logits(
    tmp_path: Path,
) -> None:
    features, baseline = _write_analysis_fixture(tmp_path)
    result = analyze_label_reliability(features, baseline, tmp_path / "analysis", device="cpu")
    payload = json.loads(result.manifest_path.read_text(encoding="utf-8"))
    payload["clips"][0]["reliability"] = "reliable"
    forged = tmp_path / "forged-classification.json"
    forged.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValueError, match="reliability does not match seed logits"):
        load_validated_reliability_manifest(
            forged,
            feature_manifest_sha256=_sha256(features / "manifest.json"),
            require_go=False,
        )


def test_analysis_rejects_a_noncanonical_e0_configuration(tmp_path: Path) -> None:
    features, baseline = _write_analysis_fixture(tmp_path)
    summary_path = baseline / "summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    summary["configuration"]["learning_rate"] = 0.5
    summary_path.write_text(json.dumps(summary), encoding="utf-8")

    with pytest.raises(ValueError, match="canonical E0 configuration"):
        analyze_label_reliability(features, baseline, tmp_path / "analysis", device="cpu")


def test_analysis_rejects_duplicate_checkpoint_paths(tmp_path: Path) -> None:
    features, baseline = _write_analysis_fixture(tmp_path)
    summary_path = baseline / "summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    summary["seeds"][1]["artifacts"]["checkpoint"] = summary["seeds"][0]["artifacts"][
        "checkpoint"
    ]
    summary_path.write_text(json.dumps(summary), encoding="utf-8")

    with pytest.raises(ValueError, match="seed 43 is not complete"):
        analyze_label_reliability(features, baseline, tmp_path / "analysis", device="cpu")


def test_analysis_rejects_a_checkpoint_that_changed_after_summary(
    tmp_path: Path,
) -> None:
    features, baseline = _write_analysis_fixture(tmp_path)
    checkpoint = baseline / "seed-42" / "best.pt"
    checkpoint.write_bytes(checkpoint.read_bytes() + b"changed")

    with pytest.raises(ValueError, match="checkpoint integrity"):
        analyze_label_reliability(features, baseline, tmp_path / "analysis", device="cpu")


def test_analysis_rejects_a_checkpoint_changed_during_inference(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    features, baseline = _write_analysis_fixture(tmp_path)
    checkpoint = baseline / "seed-42" / "best.pt"
    original_infer = reliability_module._infer_logits
    changed = False

    def mutating_infer(*args: object, **kwargs: object) -> list[tuple[float, ...]]:
        nonlocal changed
        result = original_infer(*args, **kwargs)  # type: ignore[arg-type]
        if not changed:
            checkpoint.write_bytes(checkpoint.read_bytes() + b"changed")
            changed = True
        return result

    monkeypatch.setattr(reliability_module, "_infer_logits", mutating_infer)

    with pytest.raises(RuntimeError, match="checkpoint changed after seed 42 inference"):
        analyze_label_reliability(features, baseline, tmp_path / "analysis", device="cpu")
