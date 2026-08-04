"""The binary low-vs-high view the product decision is actually judged on.

Pinned against the measured E0-10 and E0-L Test matrices, because the whole
argument for S15P11A105-289 rests on those two numbers: E0-L finds more of the
disengaged students and E0-10 is more accurate overall. If this collapse of the
matrix ever stops reproducing them, the ticket's premise stops being checkable.
"""

from __future__ import annotations

from zani_ai.engagement.contracts import (
    CONSECUTIVE_LOW_DECISIONS,
    LOW_ENGAGEMENT_CLASSES,
    low_engagement_metrics,
)

#: Pooled Test confusion matrices from `ai/results`, rows=actual, cols=predicted.
#: E0-10 is 10 seeds and E0-L 5, which does not matter here: every metric below
#: is a ratio within one matrix.
E0_10_POOLED = [
    [2007, 447, 285, 171],
    [359, 617, 864, 390],
    [278, 445, 1711, 1566],
    [39, 77, 841, 10023],
]
E0L_POOLED = [
    [945, 341, 128, 41],
    [154, 380, 441, 140],
    [126, 289, 954, 631],
    [14, 69, 641, 4766],
]


def test_the_low_engagement_pair_is_the_bottom_two_grades() -> None:
    assert LOW_ENGAGEMENT_CLASSES == (0, 1)


def test_it_reproduces_the_measured_e0_10_and_e0l_figures() -> None:
    baseline = low_engagement_metrics(E0_10_POOLED)
    ordinal = low_engagement_metrics(E0L_POOLED)

    assert round(baseline["recall"], 4) == 0.6673
    assert round(baseline["false_positive_rate"], 4) == 0.0560
    assert round(ordinal["recall"], 4) == 0.7082
    assert round(ordinal["false_positive_rate"], 4) == 0.0665
    # The trade the ticket exists to price: +4.1%p detection for +1.05%p false
    # positives and, separately, 1.3%p of 4-class accuracy.
    assert round(ordinal["recall"] - baseline["recall"], 4) == 0.0409


def test_three_consecutive_windows_is_a_cubed_rate() -> None:
    """The independence approximation, stated so a change to it is visible.

    90 minutes of 10-second windows is 540 decisions, so a per-window
    false-positive rate `f` becomes `f**3 * 540` alarms a lesson.
    """
    metrics = low_engagement_metrics(E0_10_POOLED)

    assert CONSECUTIVE_LOW_DECISIONS == 3
    assert round(metrics["consecutive_detection_rate"], 6) == round(metrics["recall"] ** 3, 6)
    assert round(metrics["false_alarms_per_90min"], 6) == round(
        metrics["false_positive_rate"] ** 3 * 540, 6
    )
    # Both baselines sit far inside the 0.5-per-lesson budget, so the gate that
    # matters for E0-M is recall, not false alarms.
    assert metrics["false_alarms_per_90min"] < 0.5


def test_a_perfect_and_an_empty_matrix_do_not_divide_by_zero() -> None:
    perfect = low_engagement_metrics([[5, 0, 0, 0], [0, 5, 0, 0], [0, 0, 5, 0], [0, 0, 0, 5]])
    assert perfect == {
        "recall": 1.0,
        "false_positive_rate": 0.0,
        "precision": 1.0,
        "f1": 1.0,
        "consecutive_detection_rate": 1.0,
        "false_alarms_per_90min": 0.0,
    }

    empty = low_engagement_metrics([[0] * 4 for _ in range(4)])
    assert set(empty.values()) == {0.0}
