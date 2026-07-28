"""Locks how summary/test aggregation handles the ordinal metrics.

Both aggregates must propagate within-1 accuracy and QWK, and both must
survive records written before those metrics existed.
"""

from __future__ import annotations

import pytest

from zani_ai.engagement.contracts import LABELS
from zani_ai.engagement.experiment import _aggregate as summary_aggregate
from zani_ai.engagement.report import _aggregate as report_aggregate


def _seed_record(accuracy: float, *, ordinal: bool) -> dict[str, object]:
    validation: dict[str, object] = {"accuracy": accuracy, "macro_f1": 0.5}
    if ordinal:
        validation["within_one_accuracy"] = 0.9
        validation["quadratic_weighted_kappa"] = 0.7
    return {"seed": 42, "validation": validation}


def test_summary_aggregate_includes_ordinal_metrics_when_present() -> None:
    aggregate = summary_aggregate([_seed_record(0.6, ordinal=True)])

    assert aggregate["validation_within_one_accuracy"]["mean"] == pytest.approx(0.9)
    assert aggregate["validation_quadratic_weighted_kappa"]["mean"] == pytest.approx(0.7)


def test_summary_aggregate_tolerates_records_written_before_ordinal_metrics() -> None:
    """Resuming from a seed record completed before the metrics must not fail."""
    aggregate = summary_aggregate(
        [_seed_record(0.6, ordinal=True), _seed_record(0.7, ordinal=False)]
    )

    assert aggregate["validation_accuracy"]["mean"] == pytest.approx(0.65)
    assert aggregate["validation_within_one_accuracy"]["mean"] == pytest.approx(0.9)


def _test_record(accuracy: float, *, ordinal: bool) -> dict[str, object]:
    report = {
        label: {"precision": 0.5, "recall": 0.5, "f1-score": 0.5, "support": 10.0}
        for label in LABELS
    }
    identity = [[1, 0, 0, 0], [0, 1, 0, 0], [0, 0, 1, 0], [0, 0, 0, 1]]
    test: dict[str, object] = {
        "accuracy": accuracy,
        "macro_f1": 0.5,
        "confusion_matrix": identity,
        "classification_report": report,
    }
    if ordinal:
        test["within_one_accuracy"] = 0.9
        test["quadratic_weighted_kappa"] = 0.7
    return {"test": test}


def test_report_aggregate_includes_ordinal_metrics_only_when_all_seeds_have_them() -> None:
    with_metrics = report_aggregate(
        [_test_record(0.6, ordinal=True), _test_record(0.7, ordinal=True)]
    )
    mixed = report_aggregate(
        [_test_record(0.6, ordinal=True), _test_record(0.7, ordinal=False)]
    )

    assert with_metrics["test_within_one_accuracy"]["mean"] == pytest.approx(0.9)
    assert with_metrics["test_quadratic_weighted_kappa"]["mean"] == pytest.approx(0.7)
    assert "test_within_one_accuracy" not in mixed
