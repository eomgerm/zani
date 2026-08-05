from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path


def _write_validation_run(
    root: Path,
    protocol: str,
    schema: str,
    accuracies: list[float],
) -> None:
    root.mkdir()
    feature_manifest = root / "feature-manifest.json"
    feature_manifest.write_text(
        json.dumps(
            {
                "schema": schema,
                "provenance": {"raw_manifest_sha256": "same-clean-raw"},
                "included": [
                    {"clip_id": "clip", "split": "valid", "feature_path": "valid/clip.npz"}
                ],
            }
        ),
        encoding="utf-8",
    )
    seeds = []
    seed_ids = tuple(range(42, 42 + len(accuracies)))
    for seed, accuracy in zip(seed_ids, accuracies, strict=True):
        seeds.append(
            {
                "seed": seed,
                "status": "complete",
                "validation": {
                    "accuracy": accuracy,
                    "macro_f1": accuracy - 0.1,
                    "within_one_accuracy": 0.9,
                    "quadratic_weighted_kappa": accuracy - 0.05,
                },
            }
        )
    (root / "summary.json").write_text(
        json.dumps(
            {
                "protocol": protocol,
                "status": "complete",
                "feature_manifest": {
                    "path": str(feature_manifest),
                    "schema": schema,
                    "sha256": f"{schema}-sha",
                },
                "environment": {
                    "pytorch": "2.11.0+cu128",
                    "cuda_device": {"name": "NVIDIA L40S"},
                },
                "seeds": seeds,
            }
        ),
        encoding="utf-8",
    )


#: Four grades, as `training.evaluate_model` always writes (it passes
#: `labels=range(4)`). Four errors per seed out of 20 clips, and the low-vs-high
#: collapse of it is recall 9/10 with a false-positive rate of 1/10.
_FIXTURE_CONFUSION = [
    [4, 1, 0, 0],
    [0, 4, 1, 0],
    [0, 0, 4, 1],
    [0, 1, 0, 4],
]


def _write_test_results(
    root: Path, protocol: str, manifest_sha: str, seed_count: int = 5
) -> None:
    seeds = []
    for seed in range(42, 42 + seed_count):
        seeds.append(
            {
                "seed": seed,
                "test": {
                    "accuracy": 0.7,
                    "macro_f1": 0.65,
                    "within_one_accuracy": 0.9,
                    "quadratic_weighted_kappa": 0.6,
                    "confusion_matrix": _FIXTURE_CONFUSION,
                },
            }
        )
    (root / "test_results.json").write_text(
        json.dumps(
            {
                "protocol": f"{protocol}-fixed-checkpoint-test",
                "status": "complete",
                "feature_manifest_sha256": manifest_sha,
                "seeds": seeds,
            }
        ),
        encoding="utf-8",
    )


def test_validation_comparison_uses_accuracy_target_on_the_same_raw_population(
    tmp_path: Path,
) -> None:
    baseline = tmp_path / "baseline"
    variant = tmp_path / "variant"
    _write_validation_run(
        baseline,
        "E0",
        "mediapipe_98_v1",
        [0.60, 0.61, 0.62, 0.63, 0.64],
    )
    _write_validation_run(
        variant,
        "E0-J",
        "mediapipe_98_placeholder_v1",
        [0.625, 0.635, 0.645, 0.655, 0.665],
    )
    script = Path(__file__).parents[2] / "scripts" / "compare_protocols.py"

    result = subprocess.run(
        [
            sys.executable,
            str(script),
            "--baseline",
            str(baseline),
            "--variant",
            str(variant),
            "--split",
            "validation",
            "--minimum-accuracy-gain",
            "0.02",
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )

    assert result.returncode == 0, result.stderr
    assert "E0-J vs E0 (Validation, 5 seeds)" in result.stdout
    assert "+0.0250 (+2.50%p)" in result.stdout
    assert "판정: 성공" in result.stdout
    assert "비교는 유효하지 않습니다" not in result.stdout


def _run_validation_comparison(
    baseline: Path, variant: Path
) -> subprocess.CompletedProcess[str]:
    script = Path(__file__).parents[2] / "scripts" / "compare_protocols.py"
    return subprocess.run(
        [
            sys.executable,
            str(script),
            "--baseline",
            str(baseline),
            "--variant",
            str(variant),
            "--split",
            "validation",
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )


def test_a_difference_under_one_percentage_point_is_suspended_not_decided(
    tmp_path: Path,
) -> None:
    """S15P11A105-238's rule: below 1%p the comparison reports 보류.

    +0.60%p with a seed sd near 0.01 is the exact shape that produced a
    1.55%p ranking of nine indistinguishable protocols, so a sign this clean
    must still not read as 성공 or 실패.
    """
    baseline = tmp_path / "baseline"
    variant = tmp_path / "variant"
    _write_validation_run(baseline, "E0", "mediapipe_98_v1", [0.60, 0.61, 0.62, 0.63, 0.64])
    _write_validation_run(
        variant, "E0-M", "mediapipe_98_v1", [0.606, 0.616, 0.626, 0.636, 0.646]
    )

    result = _run_validation_comparison(baseline, variant)

    assert result.returncode == 0, result.stderr
    assert "판정: 보류" in result.stdout
    assert "판정: 성공" not in result.stdout
    assert "판정: 실패" not in result.stdout


def test_the_detectable_minimum_difference_shrinks_as_seeds_are_added(
    tmp_path: Path,
) -> None:
    """The 검출한계 column is what makes the seed raise visible in the output.

    Same spread on both sides, 5 + 5 against 10 + 10: the reported limit must
    fall, and by roughly the sqrt(2) the two-sample formula predicts.
    """
    five = [0.60, 0.61, 0.62, 0.63, 0.64]
    ten = [0.60, 0.61, 0.62, 0.63, 0.64, 0.60, 0.61, 0.62, 0.63, 0.64]

    small = tmp_path / "small"
    small_variant = tmp_path / "small-variant"
    _write_validation_run(small, "E0", "mediapipe_98_v1", five)
    _write_validation_run(small_variant, "E0-M", "mediapipe_98_v1", five)
    large = tmp_path / "large"
    large_variant = tmp_path / "large-variant"
    _write_validation_run(large, "E0-10", "mediapipe_98_v1", ten)
    _write_validation_run(large_variant, "E0-M", "mediapipe_98_v1", ten)

    small_result = _run_validation_comparison(small, small_variant)
    large_result = _run_validation_comparison(large, large_variant)

    assert small_result.returncode == 0, small_result.stderr
    assert large_result.returncode == 0, large_result.stderr
    assert "검출한계" in small_result.stdout
    assert "5 + 5" in small_result.stdout
    assert "10 + 10" in large_result.stdout
    assert _first_detection_limit(small_result.stdout) > _first_detection_limit(
        large_result.stdout
    )


def _first_detection_limit(output: str) -> float:
    """The accuracy row's 검출한계 cell, as a percentage-point float."""
    for line in output.splitlines():
        if line.startswith("accuracy"):
            return float(line.split()[-1].removesuffix("%p"))
    raise AssertionError(f"no accuracy row in:\n{output}")


def test_pooled_error_counts_are_reported_per_seed_when_the_counts_differ(
    tmp_path: Path,
) -> None:
    """A 10-seed protocol pools twice the errors of a 5-seed one at equal quality.

    Both sides here have identical per-seed confusion matrices, so the honest
    reading is "no change". The raw pooled totals say 20 → 40, which is why the
    per-seed rate has to be on the same line.
    """
    baseline = tmp_path / "baseline"
    variant = tmp_path / "variant"
    _write_validation_run(baseline, "E0", "mediapipe_98_v1", [0.6, 0.61, 0.62, 0.63, 0.64])
    _write_validation_run(variant, "E0-10", "mediapipe_98_v1", [0.6, 0.61, 0.62, 0.63, 0.64] * 2)
    _write_test_results(baseline, "E0", "mediapipe_98_v1-sha")
    _write_test_results(variant, "E0-10", "mediapipe_98_v1-sha", seed_count=10)
    script = Path(__file__).parents[2] / "scripts" / "compare_protocols.py"

    result = subprocess.run(
        [sys.executable, str(script), "--baseline", str(baseline), "--variant", str(variant)],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )

    assert result.returncode == 0, result.stderr
    # Four errors per seed in `_FIXTURE_CONFUSION`.
    assert "E0 20 (5 seeds, 4.0/seed)" in result.stdout
    assert "E0-10 40 (10 seeds, 4.0/seed)" in result.stdout
    assert "(5 seeds 합산, 행=정답, 열=예측)" in result.stdout
    assert "(10 seeds 합산, 행=정답, 열=예측)" in result.stdout


def test_test_comparison_accepts_different_features_from_the_same_raw_population(
    tmp_path: Path,
) -> None:
    baseline = tmp_path / "baseline"
    variant = tmp_path / "variant"
    _write_validation_run(baseline, "E0", "mediapipe_98_v1", [0.6] * 5)
    _write_validation_run(
        variant,
        "E0-J",
        "mediapipe_98_placeholder_v1",
        [0.62] * 5,
    )
    _write_test_results(baseline, "E0", "mediapipe_98_v1-sha")
    _write_test_results(variant, "E0-J", "mediapipe_98_placeholder_v1-sha")
    script = Path(__file__).parents[2] / "scripts" / "compare_protocols.py"

    result = subprocess.run(
        [
            sys.executable,
            str(script),
            "--baseline",
            str(baseline),
            "--variant",
            str(variant),
        ],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )

    assert result.returncode == 0, result.stderr
    assert "E0-J vs E0 (Test, 5 seeds)" in result.stdout
    assert "비교는 유효하지 않습니다" not in result.stdout


def _write_confusion_only_results(
    root: Path, protocol: str, confusion: list[list[int]], seed_count: int = 5
) -> None:
    """A finalized run whose only varying quantity is the confusion matrix."""
    root.mkdir(parents=True, exist_ok=True)
    (root / "test_results.json").write_text(
        json.dumps(
            {
                "protocol": f"{protocol}-fixed-checkpoint-test",
                "status": "complete",
                "feature_manifest_sha256": "sha",
                "seeds": [
                    {
                        "seed": seed,
                        "test": {
                            "accuracy": sum(confusion[index][index] for index in range(4))
                            / sum(sum(row) for row in confusion),
                            "macro_f1": 0.65,
                            "within_one_accuracy": 0.9,
                            "quadratic_weighted_kappa": 0.6,
                            # A seed-dependent nudge, so `_welch` and the
                            # detectable minimum are defined rather than degenerate.
                            "confusion_matrix": _nudge(confusion, seed),
                        },
                    }
                    for seed in range(42, 42 + seed_count)
                ],
            }
        ),
        encoding="utf-8",
    )


def _nudge(confusion: list[list[int]], seed: int) -> list[list[int]]:
    shifted = [row.copy() for row in confusion]
    offset = seed % 3
    shifted[0][0] -= offset
    shifted[0][2] += offset
    return shifted


#: 60 low-engagement clips of 150. Recall 70.0%, false-positive rate 4.4%
#: (4 of 90 high clips), accuracy 74.7% -- roughly E0-10's shape, small enough to
#: hand-check.
_GATE_BASELINE = [
    [20, 4, 4, 2],
    [4, 14, 8, 4],
    [1, 2, 38, 4],
    [0, 1, 4, 40],
]
#: Recall 80.0% (+10%p) at the same 4 false positives, accuracy 78.7%. Clears all
#: three criteria.
_GATE_PASSING = [
    [24, 3, 2, 1],
    [5, 16, 6, 3],
    [1, 2, 38, 4],
    [0, 1, 4, 40],
]
#: The same recall gain and the same false positives, but ten Engaged/Highly
#: clips swap with each other, dropping accuracy to 72.0% (-2.67%p). Those
#: confusions never cross the low/high boundary, which is what isolates the
#: accuracy criterion from the alarm one.
_GATE_TOO_COSTLY = [
    [24, 3, 2, 1],
    [5, 16, 6, 3],
    [1, 2, 30, 12],
    [0, 1, 6, 38],
]


def test_the_low_engagement_table_and_gate_are_reported_for_test_comparisons(
    tmp_path: Path,
) -> None:
    """The S15P11A105-289 gate, on a variant built to clear all three criteria.

    The point is that the binary view is printed and judged at all: the 4-class
    table can call a change immeasurable while this one shows the trade that
    actually decides deployment.
    """
    baseline = tmp_path / "baseline"
    variant = tmp_path / "variant"
    _write_confusion_only_results(baseline, "E0-10", _GATE_BASELINE)
    _write_confusion_only_results(variant, "E0-M", _GATE_PASSING)
    script = Path(__file__).parents[2] / "scripts" / "compare_protocols.py"

    result = subprocess.run(
        [sys.executable, str(script), "--baseline", str(baseline), "--variant", str(variant)],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )

    assert result.returncode == 0, result.stderr
    assert "저참여(Not-Engaged + Barely-Engaged) 이진 판정" in result.stdout
    for label in ("저참여 recall", "저참여 FPR", "3연속 검출률", "90분당 오탐"):
        assert label in result.stdout
    # The approximation warning has to travel with the numbers it qualifies.
    assert "독립이라고 가정한 근사" in result.stdout
    assert "저참여 판정: 채택 조건 충족" in result.stdout
    assert "O 저참여 recall" in result.stdout


def test_the_gate_fails_a_variant_that_pays_too_much_accuracy(tmp_path: Path) -> None:
    """E0-L against E0-10 is this case: +4.09%p recall for -1.33%p accuracy.

    Recall and false alarms are held identical to the passing variant, so the only
    reason this one fails is the criterion the test is named after.
    """
    baseline = tmp_path / "baseline"
    variant = tmp_path / "variant"
    _write_confusion_only_results(baseline, "E0-10", _GATE_BASELINE)
    _write_confusion_only_results(variant, "E0-M", _GATE_TOO_COSTLY)
    script = Path(__file__).parents[2] / "scripts" / "compare_protocols.py"

    result = subprocess.run(
        [sys.executable, str(script), "--baseline", str(baseline), "--variant", str(variant)],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )

    assert result.returncode == 0, result.stderr
    assert "X accuracy 하락" in result.stdout
    assert "O 저참여 recall" in result.stdout
    assert "O 90분당 오탐" in result.stdout
    assert "저참여 판정: 미충족" in result.stdout
