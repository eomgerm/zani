from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import pytest
import torch

from zani_ai.engagement.export import (
    DeploymentMetadata,
    assert_output_parity,
    export_onnx,
)
from zani_ai.engagement.model import (
    EngagementTransformer,
    ModelConfig,
    ordinal_binary_class_probabilities,
)


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


#: PyTorch and ONNX Runtime outputs from E0-C seed 43, the run that first hit
#: this. Normalization clamps three near-constant features' std at 1e-6, so the
#: exporter's synthetic ramp drives activations to ~1e6 and the logits span four
#: orders of magnitude.
_OBSERVED_ORT = np.array([[24091.26, 15852.29, 30824.63, 5.367241]], dtype=np.float32)
_OBSERVED_TORCH = np.array([[24091.25, 15852.31, 30824.62, 5.370551]], dtype=np.float32)


def test_parity_accepts_accumulation_order_noise_on_a_small_logit() -> None:
    """The 3e-3 gap on the smallest logit cannot change the predicted class."""
    assert_output_parity(_OBSERVED_ORT, _OBSERVED_TORCH)


def test_per_element_relative_tolerance_would_have_rejected_it() -> None:
    """Pins why the rule changed: the old form failed this exact output."""
    with pytest.raises(AssertionError):
        np.testing.assert_allclose(_OBSERVED_ORT, _OBSERVED_TORCH, rtol=1e-4, atol=1e-5)


def test_parity_still_rejects_a_genuinely_wrong_output() -> None:
    """Loosening must not let a broken graph through."""
    broken = _OBSERVED_TORCH.copy()
    broken[0, 1] *= 1.01

    with pytest.raises(AssertionError):
        assert_output_parity(broken, _OBSERVED_TORCH)


def test_ordinal_binary_head_exports_four_repaired_class_probabilities(tmp_path: Path) -> None:
    """E0-L's completion condition: the deployed graph keeps the input shape and
    ends in the same monotonicity-repaired distribution the metrics were read from.
    """
    torch.manual_seed(237)
    model = EngagementTransformer(
        torch.zeros(98),
        torch.ones(98),
        config=ModelConfig(
            d_model=16, nhead=4, num_layers=1, mlp_dim=8, dropout=0, head="ordinal_binary"
        ),
    ).eval()

    result = export_onnx(model, DeploymentMetadata.default(), tmp_path)

    tokens = np.arange(20 * 98, dtype=np.float32).reshape(1, 20, 98) / 1000
    actual = ort.InferenceSession(str(result.model_path)).run(None, {"tokens": tokens})[0]
    expected = ordinal_binary_class_probabilities(model(torch.from_numpy(tokens))).detach().numpy()
    assert actual.shape == (1, 4)
    np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
    assert (actual >= 0).all()
    np.testing.assert_allclose(actual.sum(axis=1), np.ones(1), rtol=1e-5, atol=1e-6)
    assert json.loads(result.metadata_path.read_text(encoding="utf-8"))["input_shape"] == [
        "batch",
        20,
        98,
    ]


def test_export_succeeds_when_a_feature_std_is_clamped(tmp_path: Path) -> None:
    """End-to-end: a near-constant training feature must not block export."""
    torch.manual_seed(11)
    std = torch.ones(98)
    std[:3] = 3.3e-7

    result = export_onnx(
        EngagementTransformer(
            torch.zeros(98),
            std,
            config=ModelConfig(d_model=16, nhead=4, num_layers=1, mlp_dim=8, dropout=0),
        ).eval(),
        DeploymentMetadata.default(),
        tmp_path,
    )

    assert result.model_path.is_file()
