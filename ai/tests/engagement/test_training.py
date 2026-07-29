from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np
import pytest
import torch

from zani_ai.engagement import training as training_module
from zani_ai.engagement.model import ModelConfig
from zani_ai.engagement.training import (
    CachedFeatureDataset,
    CoralObjective,
    FeatureEntry,
    FocalObjective,
    SoftmaxObjective,
    SordObjective,
    TrainingConfig,
    _balanced_sample_weights,
    _class_weights,
    _loader,
    compute_feature_statistics,
    make_objective,
    ordinal_quality,
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
    assert 0.0 <= metrics["validation"]["within_one_accuracy"] <= 1.0
    assert "quadratic_weighted_kappa" in metrics["test"]
    assert len(metrics["validation_history"]) >= 1
    assert {"epoch", "macro_f1", "quadratic_weighted_kappa"} <= set(
        metrics["validation_history"][0]
    )


def _run_tiny_training(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, **overrides: object
) -> tuple[object, object]:
    """Train one epoch on a 4-clip manifest, returning the objective and sampler
    the loop actually built.

    Asserting on ``metrics.json`` alone would not do: its ``training`` block is
    a copy of the config, so it stays correct even if the config never reaches
    the loop. These spies wrap the real functions and observe the true calls.
    """
    features = tmp_path / "features"
    features.mkdir()
    _write_manifest(features)
    built: dict[str, object] = {}
    real_objective, real_loader = training_module.make_objective, training_module._loader

    def spy_objective(*args: object, **kwargs: object) -> object:
        objective = real_objective(*args, **kwargs)  # type: ignore[arg-type]
        # `evaluate_model` builds its own decode-only objective every epoch;
        # the training one is the first call.
        built.setdefault("objective", objective)
        return objective

    def spy_loader(dataset: object, config: object, **kwargs: object) -> object:
        loader = real_loader(dataset, config, **kwargs)  # type: ignore[arg-type]
        built.setdefault("sampler", loader.sampler)  # first call is the train split
        return loader

    monkeypatch.setattr(training_module, "make_objective", spy_objective)
    monkeypatch.setattr(training_module, "_loader", spy_loader)
    train_model(
        TrainingConfig(
            features_root=features,
            output_dir=tmp_path / "run",
            max_epochs=1,
            batch_size=4,
            patience=1,
            device="cpu",
            model=ModelConfig(d_model=16, nhead=4, num_layers=1, mlp_dim=8, dropout=0),
            **overrides,  # type: ignore[arg-type]
        )
    )
    return built["objective"], built["sampler"]


def test_focal_config_reaches_the_training_loop(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """E0-E: the loop must build a FocalObjective carrying the class weights."""
    objective, _ = _run_tiny_training(
        tmp_path, monkeypatch, class_weighting="sqrt_balanced", loss="focal", focal_gamma=3.0
    )

    assert isinstance(objective, FocalObjective)
    assert objective.gamma == 3.0
    assert objective.alpha is not None


def test_default_config_still_builds_the_plain_softmax_objective(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    objective, sampler = _run_tiny_training(tmp_path, monkeypatch)

    assert isinstance(objective, SoftmaxObjective)
    assert not isinstance(sampler, torch.utils.data.WeightedRandomSampler)


def test_sampler_config_reaches_the_training_loop(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """E0-F: the train loader must be resampled and the loss left unweighted."""
    objective, sampler = _run_tiny_training(tmp_path, monkeypatch, sampler="balanced")

    assert isinstance(sampler, torch.utils.data.WeightedRandomSampler)
    assert isinstance(objective, SoftmaxObjective)
    assert objective.criterion.weight is None


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


# --- focal loss -------------------------------------------------------------

_LOGITS = torch.tensor(
    [
        [4.0, 0.0, 0.0, 0.0],  # confident and correct -> easy
        [0.1, 0.0, -0.1, 0.0],  # nearly uniform -> hard
        [0.0, 0.0, 0.0, 3.0],  # confidently wrong -> hard
        [0.5, 1.5, 0.2, 0.0],
    ]
)
_LABELS = torch.tensor([0, 1, 2, 1])


def test_focal_with_gamma_zero_is_plain_cross_entropy() -> None:
    """gamma=0 removes the modulating term, leaving exactly CrossEntropyLoss."""
    focal = FocalObjective(gamma=0.0).loss(_LOGITS, _LABELS)

    expected = SoftmaxObjective().loss(_LOGITS, _LABELS)
    assert float(focal) == pytest.approx(float(expected), rel=1e-6)


def test_focal_with_gamma_zero_matches_weighted_cross_entropy() -> None:
    """alpha must use the same weighted-mean reduction as CrossEntropyLoss(weight=)."""
    alpha = torch.tensor([0.5, 1.5, 1.0, 2.0])

    focal = FocalObjective(gamma=0.0, alpha=alpha).loss(_LOGITS, _LABELS)

    expected = SoftmaxObjective(weight=alpha).loss(_LOGITS, _LABELS)
    assert float(focal) == pytest.approx(float(expected), rel=1e-6)


def test_focal_downweights_easy_samples_relative_to_hard_ones() -> None:
    """The whole point: (1-p_t)^gamma shrinks the confident-correct sample most."""
    easy, hard = _LOGITS[:1], _LOGITS[1:2]
    easy_label, hard_label = _LABELS[:1], _LABELS[1:2]
    plain, focal = SoftmaxObjective(), FocalObjective(gamma=2.0)

    easy_ratio = float(focal.loss(easy, easy_label)) / float(plain.loss(easy, easy_label))
    hard_ratio = float(focal.loss(hard, hard_label)) / float(plain.loss(hard, hard_label))

    assert easy_ratio < hard_ratio
    assert easy_ratio < 0.01  # p_t ~ 0.94, so (1-p_t)^2 ~ 0.003


def test_focal_rejects_negative_gamma() -> None:
    with pytest.raises(ValueError, match="focal_gamma must be non-negative"):
        FocalObjective(gamma=-1.0)


def test_focal_decodes_predictions_like_softmax() -> None:
    focal, plain = FocalObjective(gamma=2.0), SoftmaxObjective()

    torch.testing.assert_close(focal.predict(_LOGITS), plain.predict(_LOGITS))
    torch.testing.assert_close(focal.class_probs(_LOGITS), plain.class_probs(_LOGITS))


# --- balanced sampler -------------------------------------------------------


def test_balanced_sample_weights_give_every_class_equal_mass(tmp_path: Path) -> None:
    counts = (570, 750, 2137, 4422)
    dataset = _dataset_with_counts(tmp_path, counts)

    weights = _balanced_sample_weights(dataset).numpy()

    mass = [
        weights[sum(counts[:label]) : sum(counts[: label + 1])].sum()
        for label in range(len(counts))
    ]
    np.testing.assert_allclose(mass, [mass[0]] * len(counts), rtol=1e-6)


def _sampler_config(tmp_path: Path, **overrides: object) -> TrainingConfig:
    fields: dict[str, object] = {
        "features_root": tmp_path,
        "output_dir": tmp_path / "run",
        "batch_size": 8,
        "seed": 7,
        "device": "cpu",
        "sampler": "balanced",
        **overrides,
    }
    return TrainingConfig(**fields)  # type: ignore[arg-type]


def test_balanced_sampler_evens_out_the_drawn_class_distribution(tmp_path: Path) -> None:
    counts = (50, 50, 100, 800)
    dataset = _dataset_with_counts(tmp_path, counts)
    loader = _loader(dataset, _sampler_config(tmp_path), shuffle=True)

    drawn = np.bincount(
        [dataset.entries[i].label_index for i in loader.sampler],  # type: ignore[union-attr]
        minlength=4,
    )

    assert drawn.sum() == len(dataset)
    # Perfect balance is 25% each; sampling noise is wide but 49.9% -> ~25% is not.
    assert (drawn / drawn.sum() > 0.15).all()
    assert (drawn / drawn.sum() < 0.35).all()


def test_balanced_sampler_is_reproducible_for_a_given_seed(tmp_path: Path) -> None:
    """WeightedRandomSampler draws with replacement; without a seeded generator
    the protocol would not reproduce."""
    dataset = _dataset_with_counts(tmp_path, (10, 20, 30, 40))

    first = list(_loader(dataset, _sampler_config(tmp_path), shuffle=True).sampler)  # type: ignore[union-attr]
    same = list(_loader(dataset, _sampler_config(tmp_path), shuffle=True).sampler)  # type: ignore[union-attr]
    other = list(
        _loader(dataset, _sampler_config(tmp_path, seed=8), shuffle=True).sampler  # type: ignore[union-attr]
    )

    assert first == same
    assert first != other


def test_evaluation_loaders_are_never_resampled(tmp_path: Path) -> None:
    """Resampling validation would change what Macro F1 is measured on."""
    dataset = _dataset_with_counts(tmp_path, (10, 20, 30, 40))

    loader = _loader(dataset, _sampler_config(tmp_path), shuffle=False)

    assert not isinstance(loader.sampler, torch.utils.data.WeightedRandomSampler)


def test_unknown_sampler_scheme_is_rejected(tmp_path: Path) -> None:
    dataset = _dataset_with_counts(tmp_path, (1, 1, 1, 1))

    with pytest.raises(ValueError, match="sampler must be one of"):
        _loader(dataset, _sampler_config(tmp_path, sampler="oversample"), shuffle=True)


# --- ordinal quality metrics --------------------------------------------------


def test_ordinal_quality_hand_computed_values() -> None:
    """truth 0,1,2,3 / pred 0,2,0,3: distances 0,1,2,0 -> within-1 = 3/4.

    QWK by hand: observed disagreement sum(w*O) = 1 + 4 = 5 (w = squared
    distance), expected disagreement sum(w*E) = 12 (truth marginals uniform,
    pred marginals [2,0,1,1]).
    """
    within_one, kappa = ordinal_quality([0, 1, 2, 3], [0, 2, 0, 3])

    assert within_one == pytest.approx(0.75)
    assert kappa == pytest.approx(1 - 5 / 12)


def test_ordinal_quality_degenerate_split_is_zero_not_nan() -> None:
    """A single class on both sides has zero chance-correction variance, so
    sklearn returns NaN."""
    within_one, kappa = ordinal_quality([2, 2], [2, 2])

    assert within_one == 1.0
    assert kappa == 0.0


# --- SORD soft ordinal targets ------------------------------------------------


def _sord_targets_by_hand(label: int, alpha: float, num_classes: int = 4) -> list[float]:
    """``exp(-alpha d^2)`` normalized, written out the way the paper states it.

    The implementation reaches the same distribution through a softmax over the
    negated penalties, so this is an independent second derivation rather than a
    copy of it.
    """
    weights = [math.exp(-alpha * (label - grade) ** 2) for grade in range(num_classes)]
    total = sum(weights)
    return [weight / total for weight in weights]


@pytest.mark.parametrize("label", [0, 1, 2, 3])
def test_sord_targets_match_the_paper_formula(label: int) -> None:
    targets = SordObjective(alpha=2.0).soft_targets(torch.tensor([label]))

    np.testing.assert_allclose(
        targets.numpy()[0], _sord_targets_by_hand(label, 2.0), rtol=1e-6
    )
    assert float(targets.sum()) == pytest.approx(1.0)


def test_sord_targets_peak_on_the_true_grade_and_fall_off_by_distance() -> None:
    """The point of the encoding: a neighbour keeps mass, a far grade does not."""
    targets = SordObjective(alpha=2.0).soft_targets(torch.tensor([1]))[0]

    assert int(targets.argmax()) == 1
    # grade 0 and grade 2 are both one step from 1, so they must be equal.
    assert float(targets[0]) == pytest.approx(float(targets[2]))
    assert float(targets[0]) > float(targets[3])
    assert float(targets[1]) > 0.5


def test_large_alpha_converges_on_one_hot_and_small_alpha_on_uniform() -> None:
    """alpha is the knob between the two degenerate ends, which is why it is
    part of the protocol identity instead of a free parameter."""
    labels = torch.tensor([2])

    sharp = SordObjective(alpha=50.0).soft_targets(labels)[0]
    flat = SordObjective(alpha=1e-6).soft_targets(labels)[0]

    np.testing.assert_allclose(sharp.numpy(), [0, 0, 1, 0], atol=1e-6)
    np.testing.assert_allclose(flat.numpy(), [0.25] * 4, atol=1e-6)


def test_sord_loss_is_the_soft_target_cross_entropy() -> None:
    """-sum(target * log softmax(out)), averaged over the batch."""
    objective = SordObjective(alpha=2.0)

    loss = objective.loss(_LOGITS, _LABELS)

    log_probabilities = _LOGITS.log_softmax(dim=1)
    expected = -sum(
        sum(
            target * float(log_probabilities[row, grade])
            for grade, target in enumerate(_sord_targets_by_hand(int(label), 2.0))
        )
        for row, label in enumerate(_LABELS)
    ) / len(_LABELS)
    assert float(loss) == pytest.approx(expected, rel=1e-6)


def test_sord_with_a_near_one_hot_target_reduces_to_cross_entropy() -> None:
    """A sanity anchor: as the target sharpens, the loss must approach plain CE."""
    sord = SordObjective(alpha=100.0).loss(_LOGITS, _LABELS)

    plain = SoftmaxObjective().loss(_LOGITS, _LABELS)
    assert float(sord) == pytest.approx(float(plain), rel=1e-6)


def test_sord_decodes_predictions_like_softmax() -> None:
    """The head and the decision rule are untouched -- only the target moves."""
    sord, plain = SordObjective(alpha=2.0), SoftmaxObjective()

    torch.testing.assert_close(sord.predict(_LOGITS), plain.predict(_LOGITS))
    torch.testing.assert_close(sord.class_probs(_LOGITS), plain.class_probs(_LOGITS))


@pytest.mark.parametrize("alpha", [0.0, -1.0])
def test_sord_rejects_a_non_positive_alpha(alpha: float) -> None:
    with pytest.raises(ValueError, match="sord_alpha must be positive"):
        SordObjective(alpha=alpha)


def test_make_objective_builds_sord_for_the_softmax_head() -> None:
    objective = make_objective(ModelConfig(), target_encoding="sord", sord_alpha=3.0)

    assert isinstance(objective, SordObjective)
    assert objective.alpha == 3.0
    assert objective.num_classes == 4


def test_make_objective_defaults_to_one_hot() -> None:
    assert isinstance(make_objective(ModelConfig()), SoftmaxObjective)


def test_make_objective_rejects_an_unknown_target_encoding() -> None:
    with pytest.raises(ValueError, match="target_encoding must be one of"):
        make_objective(ModelConfig(), target_encoding="soft")


def test_sord_cannot_be_combined_with_class_weighting() -> None:
    """A per-class weight is defined against a hard label the target no longer has."""
    weights = torch.tensor([0.5, 1.5, 1.0, 2.0])

    with pytest.raises(ValueError, match="cannot be combined with class weighting"):
        make_objective(ModelConfig(), weights, target_encoding="sord")


def test_sord_cannot_be_combined_with_focal_loss() -> None:
    """Both rewrite what the loss compares against; stacking them would leave
    neither attributable."""
    with pytest.raises(ValueError, match="require loss='cross_entropy'"):
        make_objective(ModelConfig(), loss="focal", target_encoding="sord")


def test_sord_is_refused_on_the_coral_head() -> None:
    """CORAL's targets are cumulative indicators, so silently ignoring the
    request would record a target encoding the run never trained with."""
    with pytest.raises(ValueError, match="not applicable to the CORAL head"):
        make_objective(ModelConfig(head="coral"), target_encoding="sord")

    assert isinstance(make_objective(ModelConfig(head="coral")), CoralObjective)


def test_sord_config_reaches_the_training_loop(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """E0-H: the loop must build a SordObjective carrying the spec's alpha."""
    objective, sampler = _run_tiny_training(
        tmp_path, monkeypatch, target_encoding="sord", sord_alpha=1.5
    )

    assert isinstance(objective, SordObjective)
    assert objective.alpha == 1.5
    assert not isinstance(sampler, torch.utils.data.WeightedRandomSampler)
