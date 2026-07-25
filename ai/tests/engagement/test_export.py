from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

from zani_ai.engagement.export import DeploymentMetadata, export_onnx
from zani_ai.engagement.model import EngagementTransformer, ModelConfig


def test_exported_onnx_matches_pytorch_and_writes_metadata(tmp_path: Path) -> None:
    torch.manual_seed(7)
    model = EngagementTransformer(
        torch.zeros(98),
        torch.ones(98),
        config=ModelConfig(d_model=16, nhead=4, num_layers=1, mlp_dim=8, dropout=0),
    ).eval()
    metadata = DeploymentMetadata.default()

    result = export_onnx(model, metadata, tmp_path)

    tokens = np.arange(20 * 98, dtype=np.float32).reshape(1, 20, 98) / 1000
    expected = model(torch.from_numpy(tokens)).detach().numpy()
    actual = ort.InferenceSession(str(result.model_path)).run(None, {"tokens": tokens})[0]
    np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
    payload = json.loads(result.metadata_path.read_text(encoding="utf-8"))
    assert payload["schema"] == "mediapipe_98_v1"
    assert payload["input_shape"] == ["batch", 20, 98]
    assert payload["labels"][0] == "Not-Engaged"
