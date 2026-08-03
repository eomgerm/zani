#!/usr/bin/env python
"""Compare two protocols' Validation or finalized Test results seed by seed.

``finalize-*`` writes ``test_results.json`` per protocol, but a go/no-go call
needs the two side by side: mean +- sd per metric, the paired difference,
whether that difference survives a Welch t-test, and -- since
S15P11A105-238 -- the smallest difference the seed counts in hand could have
detected at all. Doing it by hand per ticket is how a comparison table ends up
unreproducible.

The adjacent-error share is computed here rather than stored, because it is a
view of the confusion matrix (``|i - j| == 1`` cells over all off-diagonal
cells) that every protocol since E0 has been judged on.

    uv run python scripts/compare_protocols.py \
        --baseline artifacts/engagement/e0 \
        --variant artifacts/engagement/e0h

Add ``--split validation`` before finalization to compare the per-seed
validation records in ``summary.json`` without reading Test.
"""

from __future__ import annotations

import argparse
import json
import math
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

VALIDATION_METRICS = {
    "accuracy": "accuracy",
    "macro_f1": "macro-F1",
    "quadratic_weighted_kappa": "QWK",
    "within_one_accuracy": "within-1",
}

#: Smallest difference this family will call a result, from S15P11A105-238.
#:
#: Nine of the ten protocols measured before that ticket sat inside a 1.55%p
#: band of Validation macro-F1 while the seed standard deviation was
#: 0.010~0.012, so their ranking was noise being read as a finding. Anything
#: under this bar is reported as 보류 rather than 채택 or 기각, however clean the
#: sign looks.
DECISION_THRESHOLD = 0.01

#: ``z_0.975 + z_0.80`` -- the two-sided alpha 0.05, 80% power constant. Normal
#: approximation rather than a noncentral t, which slightly understates the
#: requirement at these sample sizes; that direction is the safe one for a bar
#: that exists to stop over-reading.
_POWER_CONSTANT = float(stats.norm.isf(0.025) + stats.norm.isf(0.20))


@dataclass(frozen=True, slots=True)
class Protocol:
    label: str
    directory: Path
    manifest_sha256: str
    raw_population: tuple[str, tuple[tuple[str, str], ...]] | None
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


@dataclass(frozen=True, slots=True)
class ValidationProtocol:
    label: str
    directory: Path
    manifest_sha256: str
    raw_population: tuple[str, tuple[tuple[str, str], ...]] | None
    seeds: tuple[int, ...]
    metrics: dict[str, list[float]]
    runtime: str


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
        raw_population=_directory_raw_population(directory),
        seeds=tuple(int(record["seed"]) for record in records),
        metrics=metrics,
        adjacent_shares=[_adjacent_share(matrix) for matrix in confusions],
        pooled_confusion=np.sum(confusions, axis=0),
        derived_ordinal=derived,
        runtime=_runtime(directory),
    )


def _raw_population(summary: dict[str, Any]) -> tuple[str, tuple[tuple[str, str], ...]] | None:
    feature_record = summary.get("feature_manifest")
    if not isinstance(feature_record, dict):
        return None
    path_value = feature_record.get("path")
    if not isinstance(path_value, str):
        return None
    path = Path(path_value)
    if not path.is_file():
        return None
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None
    provenance = manifest.get("provenance")
    included = manifest.get("included")
    if not isinstance(provenance, dict) or not isinstance(included, list):
        return None
    raw_sha = provenance.get("raw_manifest_sha256")
    if not isinstance(raw_sha, str):
        return None
    clips: list[tuple[str, str]] = []
    for item in included:
        if not isinstance(item, dict):
            return None
        split, clip_id = item.get("split"), item.get("clip_id")
        if not isinstance(split, str) or not isinstance(clip_id, str):
            return None
        clips.append((split, clip_id))
    return raw_sha, tuple(sorted(clips))


def _directory_raw_population(
    directory: Path,
) -> tuple[str, tuple[tuple[str, str], ...]] | None:
    path = directory / "summary.json"
    if not path.is_file():
        return None
    try:
        summary: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None
    return _raw_population(summary)


def _load_validation(directory: Path) -> ValidationProtocol:
    path = directory / "summary.json"
    if not path.is_file():
        raise SystemExit(f"Summary not found: {path} (run reproduce-* first)")
    payload: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("status") != "complete":
        raise SystemExit(f"{path} is not complete (status={payload.get('status')!r})")
    records = payload["seeds"]
    metrics: dict[str, list[float]] = {key: [] for key in VALIDATION_METRICS}
    for record in records:
        validation = record["validation"]
        for key in VALIDATION_METRICS:
            if key not in validation:
                raise SystemExit(f"{path} seed {record['seed']} has no validation {key}")
            metrics[key].append(float(validation[key]))
    feature_record = payload.get("feature_manifest", {})
    manifest_sha = feature_record.get("sha256", "") if isinstance(feature_record, dict) else ""
    return ValidationProtocol(
        label=str(payload["protocol"]),
        directory=directory,
        manifest_sha256=str(manifest_sha),
        raw_population=_raw_population(payload),
        seeds=tuple(int(record["seed"]) for record in records),
        metrics=metrics,
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


def _detectable_difference(baseline: list[float], variant: list[float]) -> float | None:
    """Smallest true difference this seed count could detect, or None.

    ``(z_0.975 + z_0.80) * sd_pooled * sqrt(1/n1 + 1/n2)``: the two-sample
    80%-power minimum detectable effect, computed from the sds actually
    observed rather than from an assumed one, so it tracks the metric in front
    of it. A measured difference smaller than this is not evidence of no
    difference -- it is a comparison that could not have found one.

    None when it is undefined: fewer than two seeds on either side, or no
    within-group variance at all (which is a degenerate run, not a perfect
    measurement -- see :func:`_welch`).
    """
    n_baseline, n_variant = len(baseline), len(variant)
    if n_baseline < 2 or n_variant < 2:
        return None
    if statistics.pstdev(baseline) == 0 and statistics.pstdev(variant) == 0:
        return None
    pooled_variance = (
        (n_baseline - 1) * statistics.variance(baseline)
        + (n_variant - 1) * statistics.variance(variant)
    ) / (n_baseline + n_variant - 2)
    return (
        _POWER_CONSTANT
        * math.sqrt(pooled_variance)
        * math.sqrt(1 / n_baseline + 1 / n_variant)
    )


def _row(name: str, baseline: list[float], variant: list[float]) -> tuple[str, ...]:
    difference = statistics.fmean(variant) - statistics.fmean(baseline)
    test = _welch(baseline, variant)
    detectable = _detectable_difference(baseline, variant)
    return (
        name,
        _mean_sd(baseline),
        _mean_sd(variant),
        f"{difference:+.4f} ({difference * 100:+.2f}%p)",
        "t=n/a" if test is None else f"t={test[0]:+.2f}",
        "p=n/a" if test is None else f"p={test[1]:.3f}",
        "n/a" if detectable is None else f"{detectable * 100:.2f}%p",
    )


def _width(text: str) -> int:
    """Display columns, counting CJK glyphs as two -- ``len`` would misalign them."""
    return sum(2 if unicodedata.east_asian_width(character) in "WF" else 1 for character in text)


def _pad(text: str, width: int) -> str:
    return text + " " * max(0, width - _width(text))


def _detection_note(n_baseline: int, n_variant: int) -> str:
    """One line telling the reader what the 검출한계 column means for these n."""
    return (
        f"\n검출한계 = 검정력 80%·양측 유의수준 0.05에서 "
        f"이 seed 수({n_baseline} + {n_variant})가 "
        f"구별할 수 있는 최소 차이입니다.\n"
        f"차이가 검출한계보다 작으면 '차이 없음'이 아니라 '이 비교로는 알 수 없음'이며, "
        f"{DECISION_THRESHOLD * 100:.2f}%p 미만 차이로는 채택·기각 결론을 내지 않습니다."
    )


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
    """The ticketed bar: macro-F1 and QWK both rise, neither falls significantly.

    Guarded by :data:`DECISION_THRESHOLD`: a metric that moved less than 1%p
    is reported as 구별 불가 and suspends the verdict, because the sign of a
    sub-threshold difference in this family is a coin flip on the seed list.
    """
    lines: list[str] = []
    risen: list[str] = []
    inconclusive: list[str] = []
    for key in ("macro_f1", "quadratic_weighted_kappa"):
        difference = statistics.fmean(variant.metrics[key]) - statistics.fmean(
            baseline.metrics[key]
        )
        risen.append(METRICS[key] if difference > 0 else "")
        if abs(difference) < DECISION_THRESHOLD:
            inconclusive.append(METRICS[key])
            direction = "구별 불가"
        else:
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
    if inconclusive:
        lines.append(
            f"  판정: 보류 — {', '.join(inconclusive)} 차이가 판단 기준 "
            f"{DECISION_THRESHOLD * 100:.2f}%p 미만입니다"
        )
    else:
        lines.append(f"  판정: {'성공' if passed else '실패'} — macro-F1과 QWK 동시 상승 조건")
    if dropped:
        lines.append(f"  유의하게 하락한 지표(p<0.05): {', '.join(dropped)}")
    return lines


def _compare_validation(baseline_dir: Path, variant_dir: Path, minimum_gain: float) -> int:
    baseline = _load_validation(baseline_dir)
    variant = _load_validation(variant_dir)
    print(f"{variant.label} vs {baseline.label} (Validation, {len(variant.seeds)} seeds)\n")

    manifests_differ = baseline.manifest_sha256 != variant.manifest_sha256
    same_raw_population = (
        baseline.raw_population is not None
        and baseline.raw_population == variant.raw_population
    )
    if manifests_differ and not same_raw_population:
        print("!! 두 프로토콜의 raw manifest·clip 집합이 다릅니다 — 이 비교는 유효하지 않습니다\n")
    if baseline.runtime != variant.runtime:
        print("!! 두 프로토콜의 실행 환경이 다릅니다 — 차이에 런타임이 섞입니다")
        print(f"   {baseline.label}: {baseline.runtime}")
        print(f"   {variant.label}: {variant.runtime}\n")

    header = ("지표", baseline.label, variant.label, "차이", "Welch t", "p", "검출한계")
    rows = [
        _row(label, baseline.metrics[key], variant.metrics[key])
        for key, label in VALIDATION_METRICS.items()
    ]
    print(_table(header, rows))
    print(_detection_note(len(baseline.seeds), len(variant.seeds)))
    accuracy_gain = statistics.fmean(variant.metrics["accuracy"]) - statistics.fmean(
        baseline.metrics["accuracy"]
    )
    if abs(accuracy_gain) < DECISION_THRESHOLD:
        print(
            f"\n판정: 보류 — Validation accuracy 차이 {accuracy_gain * 100:+.2f}%p가 판단 기준 "
            f"{DECISION_THRESHOLD * 100:.2f}%p 미만입니다 (목표 {minimum_gain * 100:+.2f}%p)"
        )
        return 0
    passed = accuracy_gain >= minimum_gain
    print(
        f"\n판정: {'성공' if passed else '실패'} — Validation accuracy "
        f"{minimum_gain * 100:+.2f}%p 목표 (실측 {accuracy_gain * 100:+.2f}%p)"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--variant", type=Path, required=True)
    parser.add_argument("--split", choices=("test", "validation"), default="test")
    parser.add_argument("--minimum-accuracy-gain", type=float, default=0.02)
    arguments = parser.parse_args()

    if arguments.split == "validation":
        return _compare_validation(
            arguments.baseline,
            arguments.variant,
            arguments.minimum_accuracy_gain,
        )

    baseline = _load(arguments.baseline)
    variant = _load(arguments.variant)

    print(f"{variant.label} vs {baseline.label} (Test, {len(variant.seeds)} seeds)\n")
    manifests_differ = baseline.manifest_sha256 != variant.manifest_sha256
    same_raw_population = (
        baseline.raw_population is not None
        and baseline.raw_population == variant.raw_population
    )
    if manifests_differ and not same_raw_population:
        print("!! 두 프로토콜의 raw manifest·clip 집합이 다릅니다 — 이 비교는 유효하지 않습니다\n")
    if baseline.runtime != variant.runtime:
        print("!! 두 프로토콜의 실행 환경이 다릅니다 — 차이에 런타임이 섞입니다")
        print(f"   {baseline.label}: {baseline.runtime}")
        print(f"   {variant.label}: {variant.runtime}\n")
    for protocol in (baseline, variant):
        if protocol.derived_ordinal:
            print(f"   note: {protocol.label}의 QWK·within-1은 confusion matrix에서 복원했습니다")

    header = ("지표", baseline.label, variant.label, "차이", "Welch t", "p", "검출한계")
    rows = [
        _row(label, baseline.metrics[key], variant.metrics[key]) for key, label in METRICS.items()
    ]
    rows.append(_row("인접 오류 비중", baseline.adjacent_shares, variant.adjacent_shares))
    print(_table(header, rows))
    print(_detection_note(len(baseline.seeds), len(variant.seeds)))

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
