from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

from zani_ai.engagement.cli import build_parser


def _run_cli(*arguments: str) -> subprocess.CompletedProcess[str]:
    # Some help strings are Korean, so both sides of the pipe are pinned to
    # UTF-8. With the shell locale codec (cp949 on Korean Windows) decoding the
    # child's UTF-8 output, reading stdout raises UnicodeDecodeError instead.
    return subprocess.run(
        [sys.executable, "-m", "zani_ai", "engagement", *arguments],
        check=False,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )


def test_validate_reports_required_files_when_dataset_is_absent(tmp_path: Path) -> None:
    result = _run_cli("validate", "--data-root", str(tmp_path))

    assert result.returncode == 2
    assert "final_labels.csv" in result.stderr
    assert "train.txt" in result.stderr


def test_train_never_generates_sample_data(tmp_path: Path) -> None:
    missing = tmp_path / "missing"

    result = _run_cli("train", "--features", str(missing), "--output", str(tmp_path / "out"))

    assert result.returncode == 2
    assert "manifest" in result.stderr
    assert list(tmp_path.iterdir()) == []


def test_engagement_help_lists_pipeline_commands() -> None:
    assert build_parser().prog == "python -m zani_ai engagement"
    result = _run_cli("--help")

    assert result.returncode == 0
    assert "validate" in result.stdout
    assert "extract" in result.stdout
    assert "export" in result.stdout
    assert "analyze-label-reliability" in result.stdout


def test_label_reliability_analysis_reports_a_missing_manifest(tmp_path: Path) -> None:
    result = _run_cli(
        "analyze-label-reliability",
        "--features",
        str(tmp_path / "features"),
        "--baseline-output",
        str(tmp_path / "e0"),
        "--output",
        str(tmp_path / "analysis"),
        "--device",
        "cpu",
    )

    assert result.returncode == 2
    assert "manifest.json" in result.stderr


def test_reproduce_e0i_requires_the_reliability_manifest_argument(tmp_path: Path) -> None:
    result = _run_cli(
        "reproduce-e0i",
        "--features",
        str(tmp_path / "features"),
        "--output",
        str(tmp_path / "e0i"),
        "--device",
        "cpu",
    )

    assert result.returncode == 2
    assert "--reliability" in result.stderr
