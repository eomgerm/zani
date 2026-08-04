"""End-to-end wiring for E0-M: stage-1 reuse, mixing selection, ONNX parity.

The unit tests cover the mixing maths and the low-engagement collapse
separately. This module runs the real driver, and then the real finalizer, over a
tiny synthetic dataset, so a break in how they join -- which half stage 1
supplies, when the grid is searched, what the exported graph emits -- fails here
rather than after ten seeds on the training server.

The parity assertion is the one the ticket names as a completion condition:
PyTorch's ``p_safe``, a softmax over the ONNX output, and the browser's own
softmax must be the same four numbers.
"""

from __future__ import annotations

import json
import re
from dataclasses import replace
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pytest
import torch

from zani_ai.engagement.experiment import (
    E0_10_SPEC,
    E0M_SPEC,
    ExperimentSpec,
    _duration,
    reproduce_experiment,
)
from zani_ai.engagement.model import DUAL_HEAD, MixingProtocol, ModelConfig, deployment_view
from zani_ai.engagement.report import finalize_experiment
from zani_ai.engagement.training import load_checkpoint

#: Same 98D tokens and one epoch on a small encoder: this exercises wiring, not
#: learning. ``input_dim`` stays at 98 because the deployment metadata the
#: exporter validates against is the real ``mediapipe_98_v1`` schema.
_TINY_BACKBONE = {"input_dim": 98, "d_model": 16, "nhead": 4, "num_layers": 1, "mlp_dim": 8}
#: Two seeds, not one: `report._aggregate` summarizes a standard deviation, so a
#: single-seed finalize cannot run for any protocol.
_SEEDS = (42, 43)
#: A seven-point grid rather than the protocol's 31. The selection *rule* is what
#: this module tests; the full grid is a cost, and `MixingProtocol` is pinned by
#: the identity test.
_TINY_GRID = MixingProtocol(alpha_grid=(0.0, 0.5, 1.0), temperature_grid=(1.0, 2.0))

TINY_E0_10: ExperimentSpec = replace(
    E0_10_SPEC,
    seeds=_SEEDS,
    max_epochs=1,
    batch_size=4,
    patience=1,
    model_config=ModelConfig(**_TINY_BACKBONE, dropout=0),
)
TINY_E0M: ExperimentSpec = replace(
    E0M_SPEC,
    seeds=_SEEDS,
    max_epochs=1,
    batch_size=4,
    patience=1,
    model_config=ModelConfig(**_TINY_BACKBONE, dropout=0, head=DUAL_HEAD),
    mixing=_TINY_GRID,
)


def _write_features(root: Path) -> None:
    included: list[dict[str, object]] = []
    for split in ("train", "valid", "test"):
        for label in range(4):
            directory = root / "mediapipe_98_v1" / split
            directory.mkdir(parents=True, exist_ok=True)
            for repeat in range(2):
                path = directory / f"{split}-{label}-{repeat}.npz"
                rng = np.random.default_rng(label * 10 + repeat)
                tokens = rng.normal(label, 0.05, size=(20, 98)).astype(np.float32)
                np.savez_compressed(path, tokens=tokens)
                included.append(
                    {
                        "clip_id": f"{split}-{label}-{repeat}",
                        "split": split,
                        "label_index": label,
                        "feature_path": path.relative_to(root).as_posix(),
                        "source_fingerprint": f"{split}-{label}-{repeat}",
                    }
                )
    (root / "manifest.json").write_text(
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


@pytest.fixture
def features(tmp_path: Path) -> Path:
    root = tmp_path / "processed" / "engagenet"
    root.mkdir(parents=True)
    _write_features(root)
    return root


@pytest.fixture
def stage1(features: Path, tmp_path: Path) -> Path:
    """A completed E0-10 run, which is what `--stage1` points at."""
    output = tmp_path / "e0-10"
    reproduce_experiment(TINY_E0_10, features, output, device="cpu")
    return output


@pytest.fixture
def trained(features: Path, stage1: Path, tmp_path: Path) -> Path:
    output = tmp_path / "e0m"
    reproduce_experiment(TINY_E0M, features, output, device="cpu", stage1_path=stage1)
    return output


def test_stage1_supplies_the_encoder_and_the_softmax_head_unchanged(
    stage1: Path, trained: Path
) -> None:
    """`alpha = 1` is only the baseline's decision if that head is byte-identical."""
    inherited = {
        name: value.clone()
        for name, value in torch.load(stage1 / "seed-42" / "best.pt", weights_only=False)[
            "model_state"
        ].items()
    }
    after = torch.load(trained / "seed-42" / "best.pt", weights_only=False)

    assert after["model_config"]["head"] == DUAL_HEAD
    for name, value in inherited.items():
        assert bool(torch.equal(after["model_state"][name], value)), f"{name} moved"
    # And the ordinal head, which is the only thing stage 2 trains, exists.
    assert any(name.startswith("ordinal_heads.") for name in after["model_state"])


def test_the_selected_mixing_point_is_recorded_everywhere_it_is_needed(trained: Path) -> None:
    summary = json.loads((trained / "summary.json").read_text(encoding="utf-8"))
    metrics = json.loads((trained / "seed-42" / "metrics.json").read_text(encoding="utf-8"))

    assert summary["status"] == "complete"
    recorded = summary["seeds"][0]["probability_mixing"]
    selected = recorded["selected"]
    assert selected["alpha"] in _TINY_GRID.alpha_grid
    assert selected["temperature_softmax"] in _TINY_GRID.temperature_grid
    assert selected["epsilon"] == _TINY_GRID.epsilon
    # The gate is judged on these two, so the summary has to carry both sides.
    assert "recall" in recorded["validation_low_engagement"]
    assert "recall" in recorded["reference_low_engagement"]
    # The full grid stays in metrics.json: per-seed diagnostics, not identity.
    block = metrics["probability_mixing"]
    assert len(block["grid"]) == len(_TINY_GRID.points())
    assert block["selected"] == selected
    assert block["monotonicity_violation_rate"] is not None
    # And it travels with the weights, or the export would compute another point.
    model = load_checkpoint(trained / "seed-42" / "best.pt")
    assert model.probability_mixing().to_dict() == selected


def test_the_selection_either_clears_both_budgets_or_keeps_the_baseline(trained: Path) -> None:
    """Two outcomes, and no third one where a budget is quietly exceeded.

    A one-epoch model on eight validation clips misclassifies nearly everything,
    so its false-positive rate blows the alarm budget even at ``alpha = 1``: the
    baseline corner is *not* always admissible and the fallback is a live path,
    not a defensive one. Which branch this dataset lands in is not the point; that
    both branches hold their invariant is.
    """
    recorded = json.loads((trained / "summary.json").read_text(encoding="utf-8"))["seeds"][0][
        "probability_mixing"
    ]
    low = recorded["validation_low_engagement"]

    if recorded["constraints_satisfied"]:
        drop = recorded["reference_validation_accuracy"] - recorded["validation_accuracy"]
        assert drop <= _TINY_GRID.accuracy_drop_budget
        assert low["false_alarms_per_90min"] <= _TINY_GRID.false_alarm_budget_per_90min
        # Selection maximizes recall among the admissible points, and the corner
        # it is measured against is one of them, so it cannot come back lower.
        assert low["recall"] >= recorded["reference_low_engagement"]["recall"]
    else:
        # Nothing was deployable, so the baseline's own decision is what shipped.
        assert recorded["selected"] == {
            "alpha": 1.0,
            "temperature_softmax": 1.0,
            "temperature_ordinal": 1.0,
            "epsilon": _TINY_GRID.epsilon,
        }
        assert low == recorded["reference_low_engagement"]


def test_pytorch_onnx_and_the_browser_read_the_same_probabilities(trained: Path) -> None:
    """The ticket's parity condition, over the deployed consumption path.

    The browser treats the output as logits and softmaxes it, which is the same
    array operation ONNX Runtime's consumer applies, so proving these three agree
    proves the mixture ships intact.

    Batch 1, because that is the exported graph's only accepted batch and the only
    one the browser sends -- ``torch.export`` specializes a size-1 dynamic
    dimension, so every protocol in this repo exports a fixed batch of 1 despite
    the metadata naming the axis ``batch``.
    """
    seed_directory = trained / "seed-42"
    metadata = json.loads(
        (seed_directory / "onnx" / "engagement.metadata.json").read_text(encoding="utf-8")
    )
    assert metadata["input_shape"] == ["batch", 20, 98]
    assert metadata["output_name"] == "logits"

    tokens = np.arange(20 * 98, dtype=np.float32).reshape(1, 20, 98) / 1000
    model = deployment_view(load_checkpoint(seed_directory / "best.pt"))
    with torch.inference_mode():
        torch_probabilities = model(torch.from_numpy(tokens)).softmax(dim=1).numpy()

    session = ort.InferenceSession(str(seed_directory / "onnx" / "engagement.onnx"))
    output = session.run(["logits"], {"tokens": tokens})[0]
    # Exactly what `web/engagement-demo` does with what it loads.
    shifted = output - output.max(axis=1, keepdims=True)
    browser_probabilities = np.exp(shifted) / np.exp(shifted).sum(axis=1, keepdims=True)

    assert np.isfinite(output).all()
    np.testing.assert_allclose(browser_probabilities, torch_probabilities, rtol=1e-4, atol=1e-5)
    np.testing.assert_allclose(browser_probabilities.sum(axis=1), np.ones(1), rtol=1e-5, atol=1e-6)


def test_test_evaluation_scores_the_mixture_and_records_the_binary_view(
    features: Path, trained: Path
) -> None:
    """Not the ordinal half: what is deployed is what has to be measured."""
    results_path = finalize_experiment(TINY_E0M, features, trained, device="cpu")

    results = json.loads(results_path.read_text(encoding="utf-8"))
    assert results["status"] == "complete"
    low = results["aggregate"]["test_low_engagement"]
    assert low["classes"] == ["Not-Engaged", "Barely-Engaged"]
    assert set(low["pooled"]) == {
        "recall",
        "false_positive_rate",
        "precision",
        "f1",
        "consecutive_detection_rate",
        "false_alarms_per_90min",
    }
    assert low["per_seed"]["recall"].keys() == {"mean", "sample_standard_deviation"}

    # The mixture's own decision: re-deriving it here from the checkpoint and the
    # Test split would just re-run finalize, so instead assert the recorded
    # confusion matrix is a 4-class one produced by a 4-way argmax -- which the
    # ordinal head alone (3 logits) could not have produced.
    for record in results["seeds"]:
        matrix = record["test"]["confusion_matrix"]
        assert len(matrix) == 4 and all(len(row) == 4 for row in matrix)


def test_a_dual_head_without_a_grid_is_refused(
    features: Path, stage1: Path, tmp_path: Path
) -> None:
    """A dual head with nothing to select has no deployed output at all."""
    broken = replace(TINY_E0M, mixing=None)

    with pytest.raises(ValueError, match="mixing"):
        reproduce_experiment(
            broken, features, tmp_path / "broken", device="cpu", stage1_path=stage1
        )


#: The progress line a tailed remote log is read through. Pinned as a whole,
#: including field order: training happens on a JupyterHub box with no SSH access,
#: so this line is the only instrument panel a run has, and the two leading
#: metric fields are what every log this repo has produced is grepped for.
_PROGRESS_LINE = re.compile(
    r"^E0-10 seed=42 epoch=(?P<epoch>\d+)/(?P<total>\d+) "
    r"validation_accuracy=\d\.\d{6} validation_macro_f1=\d\.\d{6} "
    r"at=\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+\-]\d{2}:\d{2} "
    r"elapsed=(?P<elapsed>\d+:\d{2}:\d{2}) epoch_seconds=\d+\.\d "
    r"eta_stop=(?P<eta_stop>\d+:\d{2}:\d{2}) eta_max=(?P<eta_max>\d+:\d{2}:\d{2})$"
)


def _seconds(duration: str) -> int:
    hours, minutes, seconds = (int(part) for part in duration.split(":"))
    return hours * 3600 + minutes * 60 + seconds


def test_every_epoch_reports_when_it_ran_and_two_bounded_etas(
    features: Path, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """Driven through E0-10 rather than E0-M: the line is printed by the shared
    driver, and the single-stage protocol reaches it without a stage-1 fixture.

    Three epochs against patience 2, so the two ETAs actually differ and the
    countdown has somewhere to move.
    """
    spec = replace(TINY_E0_10, seeds=(42,), max_epochs=3, patience=2)

    reproduce_experiment(spec, features, tmp_path / "eta", device="cpu")

    output = capsys.readouterr().out
    lines = [line for line in output.splitlines() if line.startswith("E0-10 seed=42 epoch=")]
    assert lines, "no per-epoch progress was printed"
    for line in lines:
        match = _PROGRESS_LINE.match(line)
        assert match is not None, line
        assert match["total"] == "3"
        # `eta_stop` is the soonest early stopping permits and `eta_max` the whole
        # budget, so the first can never exceed the second.
        assert _seconds(match["eta_stop"]) <= _seconds(match["eta_max"])
    # And the seed's own wall time, which a per-epoch ETA cannot know: it never
    # sees ONNX export or artifact fingerprinting.
    completion = [line for line in output.splitlines() if " complete best_epoch=" in line]
    assert len(completion) == 1
    assert re.search(r" seed_duration=\d+:\d{2}:\d{2} at=\d{4}-\d{2}-\d{2}T", completion[0])


@pytest.mark.parametrize(
    ("seconds", "expected"),
    [(0, "0:00:00"), (61, "0:01:01"), (3599.6, "1:00:00"), (37230, "10:20:30")],
)
def test_durations_are_written_for_a_person(seconds: float, expected: str) -> None:
    assert _duration(seconds) == expected


def test_a_completed_seed_is_reused_on_rerun(features: Path, stage1: Path, trained: Path) -> None:
    checkpoint = trained / "seed-42" / "best.pt"
    stamp = checkpoint.stat().st_mtime_ns

    second = reproduce_experiment(TINY_E0M, features, trained, device="cpu", stage1_path=stage1)

    assert second.completed_seeds == _SEEDS
    assert checkpoint.stat().st_mtime_ns == stamp
