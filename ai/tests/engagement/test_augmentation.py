"""Time-window augmentation contracts (S15P11A105-298).

What has to hold for E0-N's numbers to mean anything: the shape the position
embedding is fixed to, the range the blendshape channels are declared in, the
finiteness `CachedFeatureDataset` refuses to load without, reproducibility under
a fixed seed, and an inactive path that is bit-identical to E0-10.
"""

from __future__ import annotations

import numpy as np
import pytest

from zani_ai.engagement import augmentation
from zani_ai.engagement.augmentation import (
    AugmentationProtocol,
    window_slicing,
    window_warping,
)
from zani_ai.engagement.contracts import AUGMENTATION_METHODS

STEPS = 20
CHANNELS = 98


def _clip(seed: int = 0) -> np.ndarray:
    """A clip whose channels span the ranges the 98-feature schema mixes."""
    rng = np.random.default_rng(seed)
    angles = rng.uniform(-1.5, 1.5, size=(STEPS, CHANNELS // 2))
    blendshapes = rng.uniform(0.0, 1.0, size=(STEPS, CHANNELS - CHANNELS // 2))
    return np.concatenate((angles, blendshapes), axis=1).astype(np.float32)


@pytest.mark.parametrize("method", AUGMENTATION_METHODS)
def test_augmentation_preserves_the_token_shape(method: str) -> None:
    """`position_embedding` is fixed at 20 tokens, so every path resamples back."""
    protocol = AugmentationProtocol(methods=(method,), probability=1.0)

    for seed in range(20):
        augmented = protocol.apply(_clip(), np.random.default_rng(seed))

        assert augmented.shape == (STEPS, CHANNELS)
        assert augmented.dtype == np.float32


@pytest.mark.parametrize("method", AUGMENTATION_METHODS)
def test_augmentation_stays_finite_and_inside_the_input_range(method: str) -> None:
    """Linear interpolation is a convex combination, so no clamp is needed.

    This is the evidence behind that decision rather than a restatement of it:
    if either method ever moved to a non-linear interpolant, the [0, 1]
    blendshape channels would overshoot and this would fail.
    """
    protocol = AugmentationProtocol(methods=(method,), probability=1.0)
    clip = _clip()
    lower, upper = clip.min(axis=0), clip.max(axis=0)

    for seed in range(20):
        augmented = protocol.apply(clip, np.random.default_rng(seed))

        assert np.isfinite(augmented).all()
        assert (augmented >= lower - 1e-6).all()
        assert (augmented <= upper + 1e-6).all()


def test_augmentation_actually_changes_the_clip() -> None:
    """Guards the inactive-path tests below: they would pass on a no-op too."""
    protocol = AugmentationProtocol(probability=1.0)
    clip = _clip()

    changed = [
        not np.array_equal(protocol.apply(clip, np.random.default_rng(seed)), clip)
        for seed in range(20)
    ]

    assert all(changed)


def test_a_fixed_seed_reproduces_the_same_draws() -> None:
    """Reproducibility is a completion condition: a rerun must match to the bit."""
    protocol = AugmentationProtocol()
    clip = _clip()

    first_rng = np.random.default_rng(42)
    second_rng = np.random.default_rng(42)
    first = [protocol.apply(clip, first_rng) for _ in range(50)]
    second = [protocol.apply(clip, second_rng) for _ in range(50)]

    assert all(np.array_equal(a, b) for a, b in zip(first, second, strict=True))


def test_consecutive_draws_differ() -> None:
    """The point of drawing per epoch: the same clip is not frozen into one variant."""
    protocol = AugmentationProtocol(probability=1.0)
    clip = _clip()
    rng = np.random.default_rng(42)

    draws = [protocol.apply(clip, rng) for _ in range(30)]

    assert any(not np.array_equal(draws[0], other) for other in draws[1:])


def test_zero_probability_returns_the_input_untouched() -> None:
    """One half of E0-10 equivalence: nothing is drawn, so nothing changes."""
    protocol = AugmentationProtocol(probability=0.0)
    clip = _clip()

    for seed in range(10):
        assert protocol.apply(clip, np.random.default_rng(seed)) is clip


def test_zero_strength_returns_the_input_untouched() -> None:
    """The other half: methods run, but degenerate parameters make them no-ops.

    `window_ratio=0` leaves no window to warp and `reduce_ratio=1.0` crops
    nothing, so E0-N at zero strength has to reproduce E0-10 exactly even though
    it consumes the augmentation RNG.
    """
    protocol = AugmentationProtocol(probability=1.0, window_ratio=0.0, reduce_ratio=1.0)
    clip = _clip()

    for seed in range(20):
        assert np.array_equal(protocol.apply(clip, np.random.default_rng(seed)), clip)


def test_at_most_one_method_reaches_a_sample(monkeypatch: pytest.MonkeyPatch) -> None:
    """The survey never composes methods, and the one paper that did found that
    mixing them needs a learned gate. `apply` must never chain the two.

    Both methods must still be reachable -- a protocol that silently only ever
    warped would also pass a "never composes" check on its own.
    """
    calls: list[str] = []

    def record(name: str, original: object) -> object:
        def wrapped(*args: object, **kwargs: object) -> object:
            calls.append(name)
            return original(*args, **kwargs)  # type: ignore[operator]

        return wrapped

    monkeypatch.setattr(
        augmentation, "window_warping", record("warp", augmentation.window_warping)
    )
    monkeypatch.setattr(
        augmentation, "window_slicing", record("slice", augmentation.window_slicing)
    )
    protocol = AugmentationProtocol(probability=1.0)
    rng = np.random.default_rng(7)

    per_sample = []
    for _ in range(200):
        calls.clear()
        protocol.apply(_clip(), rng)
        per_sample.append(tuple(calls))

    assert all(len(names) == 1 for names in per_sample)
    assert {"warp", "slice"} == {name for names in per_sample for name in names}


def test_the_window_never_covers_the_whole_clip() -> None:
    """Warping the entire span is a resample of the clip, not a local speed change."""
    clip = _clip()

    for seed in range(50):
        augmented = window_warping(
            clip, np.random.default_rng(seed), window_ratio=0.25, scales=(0.5, 2.0)
        )

        assert np.array_equal(augmented[0], clip[0])


def test_slicing_stretches_the_crop_across_the_full_length() -> None:
    """The reference implementation interpolates one step past its last sample,
    which clamps and flattens the tail. At 20 steps that plateau is visible, so
    this asks for a strictly increasing map onto [0, target - 1]."""
    ramp = np.tile(np.arange(STEPS, dtype=np.float32)[:, None], (1, CHANNELS))

    stretched = window_slicing(ramp, np.random.default_rng(3), reduce_ratio=0.9)

    assert np.all(np.diff(stretched[:, 0]) > 0)


def test_unknown_methods_are_refused() -> None:
    with pytest.raises(ValueError, match="unknown augmentation methods"):
        AugmentationProtocol(methods=("rotation",))


@pytest.mark.parametrize("probability", [-0.1, 1.1])
def test_probability_outside_the_unit_interval_is_refused(probability: float) -> None:
    with pytest.raises(ValueError, match="probability must be in"):
        AugmentationProtocol(probability=probability)
