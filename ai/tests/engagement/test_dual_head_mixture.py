"""The dual head's mixing rule, its two corners, and its numerical floor.

The wiring test drives the real protocol end to end; this module pins the maths
the protocol rests on. Three claims matter enough to fail a build:

* ``softmax(output) == p_safe``. That equality is the entire reason the frontend
  needs no change, and it is one ``log`` away from silently breaking.
* the corners are the two protocols being combined, exactly. If ``alpha = 1``
  drifted from E0-10's decision, the accuracy guard would be measured against
  something that is not the baseline.
* no ``NaN``/``Inf`` at saturation. A sigmoid at a large logit *is* exactly 0 or
  1 in float32, so ``log(0)`` is a reachable state and not a hypothetical.
"""

from __future__ import annotations

from typing import cast

import pytest
import torch
from torch.utils.data import DataLoader, TensorDataset

from zani_ai.engagement.contracts import low_engagement_metrics
from zani_ai.engagement.model import (
    DUAL_HEAD,
    DualHeadMixture,
    EngagementTransformer,
    MixingProtocol,
    ModelConfig,
    ProbabilityMixing,
    deployment_view,
    mixed_log_probabilities,
    ordinal_binary_class_probabilities,
)
from zani_ai.engagement.training import select_probability_mixing

NEUTRAL = ProbabilityMixing(0.5, 1.0, 1.0, 1e-6)
_TINY = {"d_model": 16, "nhead": 4, "num_layers": 1, "mlp_dim": 8, "dropout": 0.0}


def _dual_model() -> EngagementTransformer:
    return EngagementTransformer(
        torch.zeros(98),
        torch.ones(98),
        config=ModelConfig(**_TINY, head=DUAL_HEAD),
    ).eval()


def test_the_browser_softmax_recovers_the_mixed_probabilities() -> None:
    """`softmax(log(p_safe)) == p_safe`, which is the deployment contract."""
    softmax_logits = torch.tensor([[2.0, -1.0, 0.5, 3.0], [0.0, 0.0, 0.0, 0.0]])
    ordinal_logits = torch.tensor([[1.5, 0.2, -2.0], [-1.0, -1.0, -1.0]])

    output = mixed_log_probabilities(softmax_logits, ordinal_logits, NEUTRAL)

    recovered = output.softmax(dim=1)
    expected = 0.5 * softmax_logits.softmax(dim=1) + 0.5 * ordinal_binary_class_probabilities(
        ordinal_logits
    )
    torch.testing.assert_close(recovered, expected, rtol=1e-5, atol=1e-6)
    torch.testing.assert_close(recovered.sum(dim=1), torch.ones(2))


@pytest.mark.parametrize(
    ("alpha", "half"),
    [
        (1.0, "softmax"),
        (0.0, "ordinal"),
    ],
)
def test_each_corner_is_exactly_the_protocol_it_combines(alpha: float, half: str) -> None:
    softmax_logits = torch.tensor([[2.0, -1.0, 0.5, 3.0]])
    ordinal_logits = torch.tensor([[1.5, 0.2, -2.0]])
    mixing = ProbabilityMixing(alpha, 1.0, 1.0, 1e-6)

    probabilities = mixed_log_probabilities(softmax_logits, ordinal_logits, mixing).softmax(dim=1)

    expected = (
        softmax_logits.softmax(dim=1)
        if half == "softmax"
        else ordinal_binary_class_probabilities(ordinal_logits)
    )
    torch.testing.assert_close(probabilities, expected, rtol=1e-5, atol=1e-6)


def test_saturated_logits_stay_finite() -> None:
    """`sigmoid(-1e4)` is exactly 0, so `log(0)` is reachable without epsilon."""
    softmax_logits = torch.tensor([[1e4, -1e4, -1e4, -1e4]])
    ordinal_logits = torch.tensor([[-1e4, -1e4, -1e4]])

    output = mixed_log_probabilities(softmax_logits, ordinal_logits, NEUTRAL)

    assert torch.isfinite(output).all()
    torch.testing.assert_close(output.softmax(dim=1).sum(dim=1), torch.ones(1))


def test_zero_input_stays_finite_through_the_whole_model() -> None:
    model = _dual_model()
    model.set_probability_mixing(NEUTRAL)

    output = deployment_view(model)(torch.zeros(3, 20, 98))

    assert torch.isfinite(output).all()
    torch.testing.assert_close(output.softmax(dim=1).sum(dim=1), torch.ones(3))


def test_the_deployment_view_decodes_as_a_softmax_model() -> None:
    """Which is what makes Test evaluation measure the mixture, not one half."""
    model = _dual_model()
    model.set_probability_mixing(NEUTRAL)
    wrapped = DualHeadMixture(model)

    assert wrapped.config.head == "softmax"
    assert wrapped(torch.zeros(2, 20, 98)).shape == (2, 4)


def test_both_heads_read_one_encoder_pass() -> None:
    model = _dual_model()

    softmax_logits, ordinal_logits = model.dual_head_logits(torch.zeros(2, 20, 98))

    assert softmax_logits.shape == (2, 4)
    assert ordinal_logits.shape == (2, 3)
    pooled = model.pooled_representation(torch.zeros(2, 20, 98))
    torch.testing.assert_close(softmax_logits, model.classifier(pooled))


def test_the_dual_forward_is_the_ordinal_half() -> None:
    """Training and checkpoint selection see only the half with gradients."""
    model = _dual_model()

    assert model(torch.zeros(2, 20, 98)).shape == (2, 3)


def test_only_the_ordinal_head_is_trainable() -> None:
    model = _dual_model()

    model.freeze_backbone()

    trainable = {name for name, parameter in model.named_parameters() if parameter.requires_grad}
    assert trainable and all(name.startswith("ordinal_heads.") for name in trainable)
    # The softmax head must stay in eval mode too: `alpha = 1` has to reproduce
    # the stage-1 decision, and dropout would make it a different one per batch.
    model.train()
    assert not model.classifier.training
    assert model.ordinal_heads.training


def test_an_unselected_model_cannot_be_deployed_or_evaluated() -> None:
    """No default: a mixture nobody chose must not be able to ship."""
    model = _dual_model()

    assert not model.has_probability_mixing()
    with pytest.raises(ValueError, match="no selected mixing point"):
        deployment_view(model)


def test_a_single_half_makes_temperature_meaningless_and_the_grid_says_so() -> None:
    points = MixingProtocol(alpha_grid=(0.0, 1.0), temperature_grid=(1.0, 2.0)).points()

    # alpha=1 collapses to one point; alpha=0 keeps the ordinal temperature,
    # which does reorder the adjacent differences.
    assert [(point.alpha, point.temperature_ordinal) for point in points] == [
        (0.0, 1.0),
        (0.0, 2.0),
        (1.0, 1.0),
    ]
    assert all(point.temperature_softmax == 1.0 for point in points)


def test_a_grid_without_the_baseline_corner_is_refused() -> None:
    with pytest.raises(ValueError, match="baseline corner"):
        MixingProtocol(alpha_grid=(0.0, 0.5))


@pytest.mark.parametrize(
    "mixing",
    [
        (1.5, 1.0, 1.0, 1e-6),
        (0.5, 0.0, 1.0, 1e-6),
        (0.5, 1.0, 1.0, 0.0),
        (0.5, 1.0, 1.0, 0.5),
    ],
)
def test_an_unusable_mixing_point_is_refused(mixing: tuple[float, float, float, float]) -> None:
    with pytest.raises(ValueError):
        ProbabilityMixing(*mixing)


def test_the_grid_search_returns_the_best_admissible_point() -> None:
    """The selection rule, checked against the grid it actually scored.

    Asserting "recall is the maximum over the admissible points" rather than
    naming an expected ``alpha``: the rule is what the protocol registered, and a
    fixed answer would only pin these particular random weights.

    The alarm budget is opened up because an untrained head misclassifies most of
    a synthetic split, which busts the deployed 0.5 budget everywhere and would
    leave nothing admissible to choose between.
    """
    model = _dual_model()
    generator = torch.Generator().manual_seed(0)
    tokens = torch.randn(64, 20, 98, generator=generator)
    labels = torch.randint(0, 4, (64,), generator=generator)
    loader = DataLoader(TensorDataset(tokens, labels), batch_size=16)
    protocol = MixingProtocol(false_alarm_budget_per_90min=540.0)

    selection = select_probability_mixing(model, loader, torch.device("cpu"), protocol)

    assert selection.constraints_satisfied
    assert len(selection.grid) == len(protocol.points())
    admissible = [
        entry
        for entry in selection.grid
        if entry["false_alarms_per_90min"] <= protocol.false_alarm_budget_per_90min
        and selection.reference.accuracy - cast(float, entry["accuracy"])
        <= protocol.accuracy_drop_budget
    ]
    best = max(cast(float, entry["low_engagement_recall"]) for entry in admissible)
    chosen = low_engagement_metrics(selection.validation.confusion_matrix)
    assert chosen["recall"] == pytest.approx(best)
    # And the model now computes the point that was chosen, so what ships is what
    # was measured.
    assert model.probability_mixing() == selection.mixing


def test_the_grid_search_keeps_the_baseline_when_nothing_is_deployable() -> None:
    model = _dual_model()
    generator = torch.Generator().manual_seed(0)
    loader = DataLoader(
        TensorDataset(
            torch.randn(32, 20, 98, generator=generator),
            torch.randint(0, 4, (32,), generator=generator),
        ),
        batch_size=16,
    )
    # No alarms at all are tolerated, so no point can clear it.
    protocol = MixingProtocol(false_alarm_budget_per_90min=-1.0)

    selection = select_probability_mixing(model, loader, torch.device("cpu"), protocol)

    assert not selection.constraints_satisfied
    assert selection.mixing == protocol.reference()
    assert selection.validation.confusion_matrix == selection.reference.confusion_matrix


def test_mixing_requires_the_dual_head() -> None:
    softmax_only = EngagementTransformer(torch.zeros(98), torch.ones(98), config=ModelConfig())

    assert deployment_view(softmax_only) is softmax_only
    with pytest.raises(ValueError, match=DUAL_HEAD):
        DualHeadMixture(softmax_only)
