from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from zani_ai.engagement.model import ModelConfig
from zani_ai.engagement.training import (
    TrainingConfig,
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
