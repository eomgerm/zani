#!/usr/bin/env python
"""Compare two finalized protocols' Test results seed by seed.

``finalize-*`` writes ``test_results.json`` per protocol, but a go/no-go call
needs the two side by side: mean +- sd per metric, the paired difference, and
whether that difference survives a Welch t-test on 5 + 5 seeds. Doing it by hand
per ticket is how a comparison table ends up unreproducible.

The adjacent-error share is computed here rather than stored, because it is a
view of the confusion matrix (``|i - j| == 1`` cells over all off-diagonal
cells) that every protocol since E0 has been judged on.

    uv run python scripts/compare_protocols.py \
        --baseline artifacts/engagement/e0 \
        --variant artifacts/engagement/e0h
"""

from __future__ import annotations

import argparse
import json
import statistics
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import numpy as np
from scipy import stats

RESULTS_FILENAME = "test_results.json"

#: Metric key in the per-seed ``test`` block -> column label. macro-F1 and QWK
#: come first because the success bar is stated on those two.
METRICS = {
    "macro_f1": "macro-F1",
    "quadratic_weighted_kappa": "QWK",
    "within_one_accuracy": "within-1",
    "accuracy": "accuracy",
}


@dataclass(frozen=True, slots=True)
class Protocol:
    label: str
    directory: Path
    manifest_sha256: str
    seeds: tuple[int, ...]
    metrics: dict[str, list[float]]
    adjacent_shares: list[float]
    pooled_confusion: np.ndarray
    #: True when QWK/within-1 were recomputed from the confusion matrix because
    #: the run predates those metrics being recorded.
    derived_ordinal: bool
    runtime: str

    @property
    def error_count(self) -> int:
        return int(self.pooled_confusion.sum() - np.trace(self.pooled_confusion))


def _adjacent_share(confusion: np.ndarray) -> float:
    """Share of misclassifications that land exactly one grade away.

    Returns 0.0 for a perfect matrix, where the ratio is undefined rather than
    zero -- a protocol with no errors is not one whose errors are all distant.
    """
    grades = np.arange(confusion.shape[0])
    distance = np.abs(grades[:, None] - grades[None, :])
    errors = int(confusion.sum() - np.trace(confusion))
    if errors == 0:
        return 0.0
    return float(confusion[distance == 1].sum()) / errors


def _within_one(confusion: np.ndarray) -> float:
    grades = np.arange(confusion.shape[0])
    distance = np.abs(grades[:, None] - grades[None, :])
    return float(confusion[distance <= 1].sum()) / float(confusion.sum())


def _quadratic_weighted_kappa(confusion: np.ndarray) -> float:
    """QWK straight from the confusion matrix.

    It only ever depended on the matrix -- observed disagreement over the
    disagreement expected from the two marginals -- so protocols finalized
    before the ordinal metrics existed (E0..E0-F) can still be compared without
    retraining. Matches ``training.ordinal_quality``, including its NaN-to-zero
    handling for a degenerate split.
    """
    grades = np.arange(confusion.shape[0])
    weights = (grades[:, None] - grades[None, :]) ** 2
    total = float(confusion.sum())
    expected = np.outer(confusion.sum(axis=1), confusion.sum(axis=0)) / total
    denominator = float((weights * expected).sum())
    if denominator == 0:
        return 0.0
    return 1.0 - float((weights * confusion).sum()) / denominator


def _load(directory: Path) -> Protocol:
    path = directory / RESULTS_FILENAME
    if not path.is_file():
        raise SystemExit(f"Test results not found: {path} (run finalize-* first)")
    payload: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("status") != "complete":
        raise SystemExit(f"{path} is not complete (status={payload.get('status')!r})")
    records = payload["seeds"]
    metrics: dict[str, list[float]] = {key: [] for key in METRICS}
    confusions = []
    derived = False
    for record in records:
        test = record["test"]
        confusion = np.asarray(test["confusion_matrix"], dtype=np.int64)
        confusions.append(confusion)
        fallbacks = {
            "quadratic_weighted_kappa": _quadratic_weighted_kappa,
            "within_one_accuracy": _within_one,
        }
        for key in METRICS:
            if key in test:
                metrics[key].append(float(test[key]))
            elif key in fallbacks:
                metrics[key].append(fallbacks[key](confusion))
                derived = True
            else:
                raise SystemExit(f"{path} seed {record['seed']} has no {key}")
    return Protocol(
        # The recorded protocol carries the `-fixed-checkpoint-test` suffix that
        # `finalize` adds; the bare name is what the ticket and README use.
        label=str(payload["protocol"]).removesuffix("-fixed-checkpoint-test"),
        directory=directory,
        manifest_sha256=str(payload.get("feature_manifest_sha256", "")),
        seeds=tuple(int(record["seed"]) for record in records),
        metrics=metrics,
        adjacent_shares=[_adjacent_share(matrix) for matrix in confusions],
        pooled_confusion=np.sum(confusions, axis=0),
        derived_ordinal=derived,
        runtime=_runtime(directory),
    )


def _runtime(directory: Path) -> str:
    """PyTorch build and card from ``summary.json``, for the environment check.

    Two protocols measured under different PyTorch/CUDA builds are not a clean
    A/B: the repo's own resume rules treat those keys as numerics-changing, so a
    comparison that spans them confounds the protocol with the runtime.
    """
    path = directory / "summary.json"
    if not path.is_file():
        return "unknown"
    environment = json.loads(path.read_text(encoding="utf-8")).get("environment", {})
    card = (environment.get("cuda_device") or {}).get("name", "cpu")
    return f"{environment.get('pytorch', '?')} / {card}"


def _mean_sd(values: list[float]) -> str:
    if len(values) < 2:
        return f"{statistics.fmean(values):.4f}"
    return f"{statistics.fmean(values):.4f} ± {statistics.stdev(values):.4f}"


def _welch(baseline: list[float], variant: list[float]) -> tuple[float, float] | None:
    """Welch t-test of ``variant`` against ``baseline``, or None when undefined.

    Welch rather than Student because the two protocols have no reason to share
    a variance, and unequal variance is exactly what a changed objective can
    produce. With no within-group variance on either side the statistic is a
    division by zero that scipy reports as a huge number; that is an absent test,
    not a decisive one, so it is not printed as if it were significant.
    """
    if statistics.pstdev(baseline) == 0 and statistics.pstdev(variant) == 0:
        return None
    result = stats.ttest_ind(variant, baseline, equal_var=False)
    statistic, pvalue = float(result.statistic), float(result.pvalue)
    if not (np.isfinite(statistic) and np.isfinite(pvalue)):
        return None
    return statistic, pvalue


def _row(name: str, baseline: list[float], variant: list[float]) -> tuple[str, ...]:
    difference = statistics.fmean(variant) - statistics.fmean(baseline)
    test = _welch(baseline, variant)
    return (
        name,
        _mean_sd(baseline),
        _mean_sd(variant),
        f"{difference:+.4f} ({difference * 100:+.2f}%p)",
        "t=n/a" if test is None else f"t={test[0]:+.2f}",
        "p=n/a" if test is None else f"p={test[1]:.3f}",
    )


def _width(text: str) -> int:
    """Display columns, counting CJK glyphs as two -- ``len`` would misalign them."""
    return sum(2 if unicodedata.east_asian_width(character) in "WF" else 1 for character in text)


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _width(text))


def _table(header: tuple[str, ...], rows: list[tuple[str, ...]]) -> str:
    widths = [max(_width(cell) for cell in column) for column in zip(header, *rows, strict=True)]
    lines = [
        "  ".join(_pad(cell, width) for cell, width in zip(header, widths, strict=True)),
        "  ".join("-" * width for width in widths),
    ]
    lines.extend(
        "  ".join(_pad(cell, width) for cell, width in zip(row, widths, strict=True))
        for row in rows
    )
    return "\n".join(lines)


def _verdict(baseline: Protocol, variant: Protocol) -> list[str]:
    """The ticketed bar: macro-F1 and QWK both rise, neither falls significantly."""
    lines: list[str] = []
    risen: list[str] = []
    for key in ("macro_f1", "quadratic_weighted_kappa"):
        difference = statistics.fmean(variant.metrics[key]) - statistics.fmean(
            baseline.metrics[key]
        )
        risen.append(METRICS[key] if difference > 0 else "")
        direction = "상승" if difference > 0 else "하락"
        lines.append(f"  {METRICS[key]:9s} {direction} ({difference:+.4f})")
    dropped = []
    for key in METRICS:
        if statistics.fmean(variant.metrics[key]) >= statistics.fmean(baseline.metrics[key]):
            continue
        test = _welch(baseline.metrics[key], variant.metrics[key])
        if test is not None and test[1] < 0.05:
            dropped.append(METRICS[key])
    passed = all(risen)
    lines.append("")
    lines.append(f"  판정: {'성공' if passed else '실패'} — macro-F1과 QWK 동시 상승 조건")
    if dropped:
        lines.append(f"  유의하게 하락한 지표(p<0.05): {', '.join(dropped)}")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--variant", type=Path, required=True)
    arguments = parser.parse_args()

    baseline = _load(arguments.baseline)
    variant = _load(arguments.variant)

    print(f"{variant.label} vs {baseline.label} (Test, {len(variant.seeds)} seeds)\n")
    if baseline.manifest_sha256 != variant.manifest_sha256:
        # Different features means the two runs answer different questions, so
        # say it before the numbers rather than in a footnote.
        print("!! 두 프로토콜의 feature manifest가 다릅니다 — 이 비교는 유효하지 않습니다")
        print(f"   {baseline.label}: {baseline.manifest_sha256[:16]}")
        print(f"   {variant.label}: {variant.manifest_sha256[:16]}\n")
    if baseline.runtime != variant.runtime:
        print("!! 두 프로토콜의 실행 환경이 다릅니다 — 차이에 런타임이 섞입니다")
        print(f"   {baseline.label}: {baseline.runtime}")
        print(f"   {variant.label}: {variant.runtime}\n")
    for protocol in (baseline, variant):
        if protocol.derived_ordinal:
            print(f"   note: {protocol.label}의 QWK·within-1은 confusion matrix에서 복원했습니다")

    header = ("지표", baseline.label, variant.label, "차이", "Welch t", "p")
    rows = [
        _row(label, baseline.metrics[key], variant.metrics[key])
        for key, label in METRICS.items()
    ]
    rows.append(_row("인접 오류 비중", baseline.adjacent_shares, variant.adjacent_shares))
    print(_table(header, rows))

    print(
        f"\n오분류 총계: {baseline.label} {baseline.error_count} "
        f"→ {variant.label} {variant.error_count}"
    )
    for protocol in (baseline, variant):
        print(f"\n{protocol.label} pooled confusion matrix (행=정답, 열=예측)")
        for row in protocol.pooled_confusion:
            print("  " + " ".join(f"{int(value):5d}" for value in row))

    print()
    print("\n".join(_verdict(baseline, variant)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
