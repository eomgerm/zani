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
    for seed, accuracy in zip((42, 43, 44, 45, 46), accuracies, strict=True):
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


def _write_test_results(root: Path, protocol: str, manifest_sha: str) -> None:
    seeds = []
    for seed in (42, 43, 44, 45, 46):
        seeds.append(
            {
                "seed": seed,
                "test": {
                    "accuracy": 0.7,
                    "macro_f1": 0.65,
                    "within_one_accuracy": 0.9,
                    "quadratic_weighted_kappa": 0.6,
                    "confusion_matrix": [[8, 2], [1, 9]],
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
