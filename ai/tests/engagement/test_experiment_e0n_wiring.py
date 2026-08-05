"""End-to-end wiring for E0-N: which split is augmented, and what gets recorded.

`test_augmentation.py` covers the transforms themselves. This module runs the
real driver over a tiny synthetic dataset so that a break in how augmentation
*joins* the training loop -- reaching Validation, failing to reproduce, silently
not running at all -- fails here rather than after ten seeds on the L40S.
"""

from __future__ import annotations

import json
from dataclasses import replace
from pathlib import Path
from typing import Any, cast

import numpy as np
import pytest
import torch

from zani_ai.engagement.augmentation import AugmentationProtocol
from zani_ai.engagement.experiment import (
    E0_10_SPEC,
    E0N_SPEC,
    ExperimentSpec,
    reproduce_experiment,
)
from zani_ai.engagement.model import ModelConfig
from zani_ai.engagement.training import TrainingConfig, _load_feature_datasets

#: Wiring, not learning: a small encoder and one epoch. `input_dim` stays at 98
#: because the augmentation resamples the real token shape.
_TINY_BACKBONE = {"input_dim": 98, "d_model": 16, "nhead": 4, "num_layers": 1, "mlp_dim": 8}
_SEEDS = (42, 43)

TINY_E0_10: ExperimentSpec = replace(
    E0_10_SPEC,
    seeds=_SEEDS,
    max_epochs=2,
    batch_size=4,
    patience=2,
    model_config=ModelConfig(**_TINY_BACKBONE, dropout=0),
)
#: The real protocol's augmentation, not a copy of its arguments: if E0-N's
#: strengths move, this run moves with them.
TINY_E0N: ExperimentSpec = replace(
    TINY_E0_10, protocol="E0-N", augmentation=E0N_SPEC.augmentation
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


def _metrics(output: Path, seed: int = 42) -> dict[str, Any]:
    return cast(
        dict[str, Any],
        json.loads((output / f"seed-{seed}" / "metrics.json").read_text(encoding="utf-8")),
    )


def test_only_the_training_split_is_augmented(features: Path) -> None:
    """Augmenting Validation or Test would change the distribution the metrics
    are measured on, and every cross-protocol comparison with it."""
    datasets = _load_feature_datasets(
        features,
        train_augmentation=AugmentationProtocol(),
        augmentation_seed=42,
    )

    assert datasets.train.augmentation is not None
    assert datasets.valid.augmentation is None
    assert datasets.test is not None
    assert datasets.test.augmentation is None


def test_augmentation_does_not_write_through_the_feature_cache(features: Path) -> None:
    """The dataset hands out one cached tensor per clip across all epochs; an
    in-place transform would compound augmentation on top of augmentation."""
    datasets = _load_feature_datasets(
        features, train_augmentation=AugmentationProtocol(probability=1.0), augmentation_seed=1
    )
    original = np.load(datasets.train.entries[0].feature_path)["tokens"]

    for _ in range(10):
        datasets.train[0]

    cached = datasets.train._feature_cache[0]
    assert cached is not None
    assert np.array_equal(cached.numpy(), original)


def test_the_protocol_reproduces_and_records_both_losses(features: Path, tmp_path: Path) -> None:
    """Two completion conditions at once: a rerun of the same seed matches to
    double precision, and every epoch carries a train and a validation loss."""
    first = tmp_path / "e0n-first"
    second = tmp_path / "e0n-second"

    reproduce_experiment(TINY_E0N, features, first, device="cpu")
    reproduce_experiment(TINY_E0N, features, second, device="cpu")

    history = _metrics(first)["validation_history"]
    assert history
    for entry in history:
        assert isinstance(entry["train_loss"], float)
        assert isinstance(entry["validation_loss"], float)
    assert history == _metrics(second)["validation_history"]


def test_an_inactive_augmentation_reproduces_the_baseline(
    features: Path, tmp_path: Path
) -> None:
    """Zero strength must be indistinguishable from E0-10, which is what makes
    a difference in the real run attributable to the augmentation and not to
    some incidental change in how the training split is fed."""
    inactive = replace(
        TINY_E0N,
        protocol="E0-10",
        augmentation=AugmentationProtocol(window_ratio=0.0, reduce_ratio=1.0),
    )
    baseline_dir = tmp_path / "e0-10"
    inactive_dir = tmp_path / "e0n-inactive"

    reproduce_experiment(TINY_E0_10, features, baseline_dir, device="cpu")
    reproduce_experiment(inactive, features, inactive_dir, device="cpu")

    assert _metrics(baseline_dir)["validation"] == _metrics(inactive_dir)["validation"]


def test_augmentation_reaches_the_model_input(features: Path) -> None:
    """The negative control for the test above: with real strength, the tensors
    the loader yields must actually differ from the cached originals."""
    config = TrainingConfig(
        features_root=features,
        output_dir=features,
        seed=42,
        device="cpu",
        augmentation=AugmentationProtocol(probability=1.0),
    )
    datasets = _load_feature_datasets(
        features,
        train_augmentation=config.augmentation,
        augmentation_seed=config.seed,
    )
    original = torch.from_numpy(np.load(datasets.train.entries[0].feature_path)["tokens"])

    drawn = [datasets.train[0][0] for _ in range(10)]

    assert all(not torch.equal(tokens, original) for tokens in drawn)
