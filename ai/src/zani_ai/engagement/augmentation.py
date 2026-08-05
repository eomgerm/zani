"""Time-window augmentation for the training split (S15P11A105-298).

Both methods come from Iwana & Uchida, "An empirical survey of data
augmentation for time series classification with neural networks" (PLOS ONE
2021, arXiv:2007.15951), which measured 12 methods over 128 datasets and six
networks. Window warping had the highest average rank for VGG, ResNet and LSTM;
slicing came next. The same survey measured rotation, permutation and time
warping as *harmful*, and they are also wrong for this domain -- the 98 channels
mix gaze angles, head Euler angles and [0,1] blendshapes, so mixing axes breaks
their meaning, and the 20 segments' temporal order is the reason a Transformer
is here at all.

Three deliberate departures from that survey and its reference implementation
(`uchidalab/time_series_augmentation`), each measured against what we actually
have rather than inherited:

* ``window_ratio`` is 0.25, not the reference default 0.1. UCR series run to
  hundreds of steps; ours are 20, where 0.1 warps two steps and does nothing.
* Augmentation happens per sample per epoch, not as a static 4x expansion of the
  training set. The survey's protocol keeps the original and appends four
  augmented copies (5x the data, 20% of it original); drawing the same ratio
  on the fly costs 1x the epoch time instead of 5x and shows the network far
  more distinct variants over the ~30 epochs this family actually runs.
* ``probability`` 0.8 reproduces that 1:4 original-to-augmented ratio.

At most **one** method is applied to any one sample. The survey uses a single
method per model and names combining methods as unexplored; the one follow-up
that explored it (Oba, Matsuo & Iwana, ICPR 2022, arXiv:2111.03253) found that
mixing several methods with equal weight won on 1 of 12 datasets and needed a
learned gating network to pay off. Composing warping onto slicing would
therefore be a distribution nobody has measured.

Interpolation is linear throughout, which is what makes the [0,1] blendshape
channels safe without a clamp: a linear interpolant is a convex combination of
two samples, so it cannot leave the range its inputs are in. It is also what
keeps the output finite whenever the input is, which `CachedFeatureDataset`
requires.
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.typing import NDArray

from zani_ai.engagement.contracts import AUGMENTATION_METHODS


def _resample(series: NDArray[np.float32], length: int) -> NDArray[np.float32]:
    """Linearly resample ``(steps, channels)`` onto ``length`` evenly spaced steps.

    Vectorized over channels rather than looping `np.interp` per channel as the
    reference implementation does: at 98 channels that loop is the whole cost of
    augmenting a sample, and the arithmetic below is the same linear interpolant.
    """
    steps = series.shape[0]
    if steps == length:
        return series
    position = np.linspace(0.0, steps - 1, num=length, dtype=np.float64)
    lower = np.floor(position).astype(np.intp)
    upper = np.minimum(lower + 1, steps - 1)
    weight = (position - lower)[:, None]
    resampled: NDArray[np.float64] = series[lower] * (1.0 - weight) + series[upper] * weight
    return resampled.astype(np.float32, copy=False)


def window_warping(
    series: NDArray[np.float32],
    rng: np.random.Generator,
    *,
    window_ratio: float,
    scales: tuple[float, ...],
) -> NDArray[np.float32]:
    """Speed up or slow down one random time window, leaving the rest alone.

    A window of ``ceil(window_ratio * steps)`` steps is resampled to
    ``scale`` times its length and spliced back between the untouched prefix and
    suffix; the whole sequence is then resampled to its original length, which is
    what keeps the token count at the 20 the position embedding is fixed to.

    The window never starts at step 0 or ends at the last step, following the
    reference implementation: warping the very edge of a clip is a shift, not a
    local speed change, and the surrounding context is what makes it the latter.
    """
    steps = series.shape[0]
    window = int(np.ceil(window_ratio * steps))
    # Degenerate strengths are a no-op rather than an error: `window_ratio=0` is
    # how the protocol's inactive path is exercised against its baseline.
    if window <= 0 or window >= steps - 1:
        return series
    start = int(rng.integers(1, steps - window))
    end = start + window
    scale = float(rng.choice(np.asarray(scales, dtype=np.float64)))
    warped_length = max(2, int(window * scale))
    spliced = np.concatenate(
        (series[:start], _resample(series[start:end], warped_length), series[end:]),
        axis=0,
    )
    return _resample(spliced, steps)


def window_slicing(
    series: NDArray[np.float32],
    rng: np.random.Generator,
    *,
    reduce_ratio: float,
) -> NDArray[np.float32]:
    """Crop a random contiguous window and stretch it back to the full length.

    Unlike the reference implementation this resamples the crop across
    ``[0, target - 1]`` rather than ``[0, target]``. The reference asks
    `np.interp` for points past its last sample, which clamps and leaves the
    final steps flat -- at 300 steps that is one step of the tail, at our 20 it
    is a visible plateau on every augmented clip.
    """
    steps = series.shape[0]
    target = int(np.ceil(reduce_ratio * steps))
    if target >= steps or target < 2:
        return series
    start = int(rng.integers(0, steps - target + 1))
    return _resample(series[start : start + target], steps)


@dataclass(frozen=True, slots=True)
class AugmentationProtocol:
    """Which augmentations run, how strong they are, and how often.

    Every field is protocol identity and enters ``configuration_sha256``:
    changing any of them changes the distribution the model is fitted to, so it
    defines a new experiment rather than an edit to an existing one.
    """

    methods: tuple[str, ...] = AUGMENTATION_METHODS
    #: Chance that a sample is augmented at all. 0.8 keeps the survey's 1:4
    #: original-to-augmented ratio; the rest of the mass is split evenly over
    #: `methods`, and a sample never receives more than one of them.
    probability: float = 0.8
    window_ratio: float = 0.25
    scales: tuple[float, ...] = (0.5, 2.0)
    reduce_ratio: float = 0.9

    def __post_init__(self) -> None:
        unknown = [method for method in self.methods if method not in AUGMENTATION_METHODS]
        if unknown:
            raise ValueError(f"unknown augmentation methods: {unknown}")
        if not 0.0 <= self.probability <= 1.0:
            raise ValueError(f"probability must be in [0, 1], got {self.probability}")

    def to_dict(self) -> dict[str, object]:
        return {
            "methods": list(self.methods),
            "probability": self.probability,
            "window_ratio": self.window_ratio,
            "scales": list(self.scales),
            "reduce_ratio": self.reduce_ratio,
        }

    def apply(
        self, series: NDArray[np.float32], rng: np.random.Generator
    ) -> NDArray[np.float32]:
        """Return ``series`` augmented, or unchanged, drawing from ``rng``.

        Returns the input array itself when nothing applies; callers that hold a
        cached tensor rely on the augmented case being a fresh array, which
        `_resample` guarantees.
        """
        if not self.methods or rng.random() >= self.probability:
            return series
        method = self.methods[int(rng.integers(0, len(self.methods)))]
        if method == "window_warping":
            return window_warping(
                series, rng, window_ratio=self.window_ratio, scales=self.scales
            )
        return window_slicing(series, rng, reduce_ratio=self.reduce_ratio)


__all__ = [
    "AugmentationProtocol",
    "window_slicing",
    "window_warping",
]
