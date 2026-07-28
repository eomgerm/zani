from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pytest
import torch

from zani_ai.engagement.model import ModelConfig
from zani_ai.engagement.training import (
    CachedFeatureDataset,
    FeatureEntry,
    TrainingConfig,
    _class_weights,
    compute_feature_statistics,
    train_model,
)


def test_statistics_use_only_supplied_train_arrays() -> None:
    train = [
        np.zeros((20, 98), dtype=np.float32),
        np.full((20, 98), 2, dtype=np.float32),
    ]

    statistics = compute_feature_statistics(train)

    np.testing.assert_allclose(statistics.mean, 1)
    np.testing.assert_allclose(statistics.std, 1)


def _write_feature(root: Path, split: str, index: int, label: int) -> dict[str, object]:
    directory = root / "mediapipe_98_v1" / split
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{split}-{index}.npz"
    rng = np.random.default_rng(index + label * 100)
    tokens = rng.normal(label, 0.05, size=(20, 98)).astype(np.float32)
    np.savez_compressed(path, tokens=tokens, label_index=np.int64(label))
    return {
        "clip_id": f"{split}-{index}",
        "split": split,
        "label_index": label,
        "feature_path": path.relative_to(root).as_posix(),
        "source_fingerprint": str(index),
    }


def _write_manifest(root: Path) -> None:
    included = []
    for split in ("train", "valid", "test"):
        for label in range(4):
            included.append(_write_feature(root, split, label, label))
    (root / "manifest.json").write_text(
        json.dumps({"schema": "mediapipe_98_v1", "included": included, "excluded": []}),
        encoding="utf-8",
    )


def test_train_model_writes_best_checkpoint_and_test_metrics(tmp_path: Path) -> None:
    features = tmp_path / "features"
    output = tmp_path / "run"
    features.mkdir()
    _write_manifest(features)
    config = TrainingConfig(
        features_root=features,
        output_dir=output,
        max_epochs=1,
        batch_size=4,
        patience=1,
        device="cpu",
        model=ModelConfig(d_model=16, nhead=4, num_layers=1, mlp_dim=8, dropout=0),
    )

    result = train_model(config)

    assert result.checkpoint_path.is_file()
    assert result.metrics_path.is_file()
    metrics = json.loads(result.metrics_path.read_text(encoding="utf-8"))
    assert metrics["selection_metric"] == "validation_macro_f1"
    assert len(metrics["test"]["confusion_matrix"]) == 4


def _dataset_with_counts(tmp_path: Path, counts: tuple[int, ...]) -> CachedFeatureDataset:
    entries = tuple(
        FeatureEntry(
            clip_id=f"{label}-{index}",
            split="train",
            label_index=label,
            feature_path=tmp_path / "unused.npz",
        )
        for label, count in enumerate(counts)
        for index in range(count)
    )
    return CachedFeatureDataset(entries)


def test_balanced_weights_invert_class_frequency(tmp_path: Path) -> None:
    counts = (570, 750, 2137, 4422)
    dataset = _dataset_with_counts(tmp_path, counts)

    weights = _class_weights(dataset, torch.device("cpu"), "balanced").numpy()

    np.testing.assert_allclose(weights, sum(counts) / (4 * np.array(counts)), rtol=1e-6)
    assert weights.max() / weights.min() == pytest.approx(4422 / 570, rel=1e-6)


def test_sqrt_balanced_softens_the_correction(tmp_path: Path) -> None:
    """Full inversion can overcorrect; sqrt keeps the same ordering, less spread."""
    counts = (570, 750, 2137, 4422)
    dataset = _dataset_with_counts(tmp_path, counts)

    balanced = _class_weights(dataset, torch.device("cpu"), "balanced").numpy()
    softened = _class_weights(dataset, torch.device("cpu"), "sqrt_balanced").numpy()

    assert list(np.argsort(softened)) == list(np.argsort(balanced))
    assert 1.0 < softened.max() / softened.min() < balanced.max() / balanced.min()
    assert softened.max() / softened.min() == pytest.approx(
        (4422 / 570) ** 0.5, rel=1e-6
    )


@pytest.mark.parametrize("scheme", ["balanced", "sqrt_balanced"])
def test_weights_keep_the_loss_scale(tmp_path: Path, scheme: str) -> None:
    """sum(count_i * weight_i) == len(dataset), so the learning rate carries over."""
    counts = (570, 750, 2137, 4422)
    dataset = _dataset_with_counts(tmp_path, counts)

    weights = _class_weights(dataset, torch.device("cpu"), scheme).numpy()

    assert float(np.dot(counts, weights)) == pytest.approx(sum(counts), rel=1e-5)


def test_unknown_weighting_scheme_is_rejected(tmp_path: Path) -> None:
    dataset = _dataset_with_counts(tmp_path, (1, 1, 1, 1))

    with pytest.raises(ValueError, match="class_weighting must be one of"):
        _class_weights(dataset, torch.device("cpu"), "inverse")
