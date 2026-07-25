from __future__ import annotations

import torch

from zani_ai.engagement.model import EngagementTransformer, ModelConfig


def test_transformer_returns_four_logits_and_owns_normalization() -> None:
    model = EngagementTransformer(torch.zeros(98), torch.ones(98))

    logits = model(torch.zeros(2, 20, 98))

    assert logits.shape == (2, 4)
    buffers = dict(model.named_buffers())
    assert buffers["feature_mean"].shape == (1, 1, 98)
    assert buffers["feature_std"].shape == (1, 1, 98)


def test_transformer_supports_small_test_configuration() -> None:
    config = ModelConfig(d_model=16, nhead=4, num_layers=1, mlp_dim=8, dropout=0)
    model = EngagementTransformer(torch.zeros(98), torch.ones(98), config=config)

    assert model(torch.ones(1, 20, 98)).shape == (1, 4)
