"""End-to-end wiring for E0-L: stage-1 reuse, freezing, and ONNX export.

The unit tests cover the monotonicity repair and the freeze separately. This
module runs the real driver over a tiny synthetic dataset so a break in how
``reproduce_experiment`` joins them -- fingerprinting the stage-1 checkpoints,
handing seed n its own backbone, exporting the repaired probabilities -- fails
here rather than after five seeds on the training server.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pytest
import torch

from zani_ai.engagement.experiment import (
    E0_SPEC,
    E0L_SPEC,
    ExperimentSpec,
    reproduce_experiment,
)
from zani_ai.engagement.model import ModelConfig

#: Same 98D tokens and one epoch on a small encoder: this exercises wiring, not
#: learning. ``input_dim`` stays at 98 because the deployment metadata the
#: exporter validates against is the real ``mediapipe_98_v1`` schema.
_TINY_BACKBONE = {"input_dim": 98, "d_model": 16, "nhead": 4, "num_layers": 1, "mlp_dim": 8}
TINY_E0: ExperimentSpec = replace(
    E0_SPEC,
    seeds=(42,),
    max_epochs=1,
    batch_size=4,
    patience=1,
    model_config=ModelConfig(**_TINY_BACKBONE, dropout=0),
)
TINY_E0L: ExperimentSpec = replace(
    E0L_SPEC,
    seeds=(42,),
    max_epochs=1,
    batch_size=4,
    patience=1,
    model_config=ModelConfig(**_TINY_BACKBONE, dropout=0, head="ordinal_binary"),
)


def _write_features(root: Path) -> None:
    included: list[dict[str, object]] = []
    for split in ("train", "valid", "test"):
        for label in range(4):
            directory = root / "mediapipe_98_v1" / split
            directory.mkdir(parents=True, exist_ok=True)
            path = directory / f"{split}-{label}.npz"
            rng = np.random.default_rng(label)
            tokens = rng.normal(label, 0.05, size=(20, 98)).astype(np.float32)
            np.savez_compressed(path, tokens=tokens)
            included.append(
                {
                    "clip_id": f"{split}-{label}",
                    "split": split,
                    "label_index": label,
                    "feature_path": path.relative_to(root).as_posix(),
                    "source_fingerprint": f"{split}-{label}",
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
    """A completed one-seed E0 run, which is what `--stage1` points at."""
    output = tmp_path / "e0"
    reproduce_experiment(TINY_E0, features, output, device="cpu")
    return output


def test_stage1_is_reused_frozen_and_recorded(
    features: Path, stage1: Path, tmp_path: Path
) -> None:
    backbone = {
        name: value.clone()
        for name, value in torch.load(
            stage1 / "seed-42" / "best.pt", weights_only=False
        )["model_state"].items()
        if not name.startswith("classifier.")
    }
    output = tmp_path / "e0l"

    result = reproduce_experiment(
        TINY_E0L, features, output, device="cpu", stage1_path=stage1
    )

    summary = json.loads(result.summary_path.read_text(encoding="utf-8"))
    assert result.completed_seeds == (42,)
    assert summary["status"] == "complete"
    recorded = summary["inputs"]["stage1"]
    assert recorded["protocol_output"] == str(stage1.resolve())
    assert recorded["seeds"]["42"]["path"] == str((stage1 / "seed-42" / "best.pt").resolve())
    trained = torch.load(output / "seed-42" / "best.pt", weights_only=False)
    for name, value in backbone.items():
        assert bool(torch.equal(trained["model_state"][name], value)), f"{name} moved"
    assert trained["model_config"]["head"] == "ordinal_binary"
    metrics = json.loads((output / "seed-42" / "metrics.json").read_text(encoding="utf-8"))
    assert metrics["validation"]["monotonicity_violation_rate"] is not None


def test_the_exported_graph_emits_repaired_class_probabilities(
    features: Path, stage1: Path, tmp_path: Path
) -> None:
    """The completion condition: export works and the tensor shapes hold."""
    output = tmp_path / "e0l"

    reproduce_experiment(TINY_E0L, features, output, device="cpu", stage1_path=stage1)

    onnx_path = output / "seed-42" / "onnx" / "engagement.onnx"
    metadata = json.loads(
        (output / "seed-42" / "onnx" / "engagement.metadata.json").read_text(encoding="utf-8")
    )
    assert metadata["input_shape"] == ["batch", 20, 98]
    tokens = np.arange(20 * 98, dtype=np.float32).reshape(1, 20, 98) / 1000
    probabilities = ort.InferenceSession(str(onnx_path)).run(None, {"tokens": tokens})[0]
    assert probabilities.shape == (1, 4)
    assert (probabilities >= 0).all()
    np.testing.assert_allclose(probabilities.sum(axis=1), np.ones(1), rtol=1e-5, atol=1e-6)


def test_a_swapped_stage1_is_refused_on_rerun(
    features: Path, stage1: Path, tmp_path: Path
) -> None:
    """A different backbone is a different experiment, so its seeds cannot mix."""
    output = tmp_path / "e0l"
    reproduce_experiment(TINY_E0L, features, output, device="cpu", stage1_path=stage1)
    other = tmp_path / "e0-other"
    # A rerun of the same protocol is deterministic and would reproduce the same
    # bytes, so the swapped stage 1 has to actually differ to be detectable.
    reproduce_experiment(TINY_E0, features, other, device="cpu")
    torch.save(
        {
            **torch.load(other / "seed-42" / "best.pt", weights_only=False),
            "epoch": 99,
        },
        other / "seed-42" / "best.pt",
    )

    with pytest.raises(ValueError, match="different stage1"):
        reproduce_experiment(TINY_E0L, features, output, device="cpu", stage1_path=other)


def test_a_completed_seed_is_reused_on_rerun(
    features: Path, stage1: Path, tmp_path: Path
) -> None:
    output = tmp_path / "e0l"
    first = reproduce_experiment(TINY_E0L, features, output, device="cpu", stage1_path=stage1)
    checkpoint = output / "seed-42" / "best.pt"
    stamp = checkpoint.stat().st_mtime_ns

    second = reproduce_experiment(TINY_E0L, features, output, device="cpu", stage1_path=stage1)

    assert second.completed_seeds == first.completed_seeds
    assert checkpoint.stat().st_mtime_ns == stamp
