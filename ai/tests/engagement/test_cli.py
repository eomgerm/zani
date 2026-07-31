from __future__ import annotations

import csv
import json
import os
import subprocess
import sys
from pathlib import Path

import numpy as np

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


def test_cli_registers_placeholder_feature_and_experiment_commands() -> None:
    parser = build_parser()

    feature_args = parser.parse_args(
        [
            "build-features",
            "--raw-root",
            "raw",
            "--output",
            "features",
            "--schema",
            "mediapipe_98_placeholder_v1",
            "--data-root",
            "dataset",
        ]
    )
    reproduce_args = parser.parse_args(
        ["reproduce-e0j", "--features", "features", "--output", "artifacts"]
    )

    assert feature_args.schema == "mediapipe_98_placeholder_v1"
    assert reproduce_args.features == Path("features")


def test_audit_frame_gate_writes_mismatch_counts_by_split_and_label(
    tmp_path: Path,
) -> None:
    data_root = tmp_path / "raw"
    data_root.mkdir(parents=True)
    clips = (
        ("train_gap", "train", "Barely-Engaged", 60),
        ("valid_gap", "valid", "Engaged", 69),
        ("test_pass", "test", "Highly-Engaged", 70),
    )
    with (data_root / "final_labels.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=["clip_id", "label", "subject_id"])
        writer.writeheader()
        for clip_id, _, label, _ in clips:
            writer.writerow({"clip_id": clip_id, "label": label, "subject_id": clip_id})
    for split in ("train", "valid", "test"):
        clip_id = next(clip_id for clip_id, item_split, _, _ in clips if item_split == split)
        (data_root / f"{split}.txt").write_text(f"{clip_id}\n", encoding="utf-8")

    raw_root = tmp_path / "raw-cache"
    included = []
    for clip_id, split, _, valid_count in clips:
        feature_path = Path(split) / f"{clip_id}.npz"
        path = raw_root / feature_path
        path.parent.mkdir(parents=True, exist_ok=True)
        counts = [3] * 20
        for index in range(valid_count - 60):
            counts[index] += 1
        valid_mask = np.zeros(100, dtype=np.bool_)
        for segment, count in enumerate(counts):
            start = segment * 5
            valid_mask[start : start + count] = True
        np.savez_compressed(
            path,
            valid_mask=valid_mask,
            timestamps_ms=np.arange(0, 10_000, 100, dtype=np.int32),
        )
        included.append(
            {
                "clip_id": clip_id,
                "split": split,
                "feature_path": feature_path.as_posix(),
                "source_fingerprint": "fixture",
            }
        )
    (raw_root / "manifest.json").write_text(
        json.dumps(
            {
                "schema": "raw_frames_v1",
                "status": "complete",
                "complete": True,
                "included": included,
                "excluded": [],
            }
        ),
        encoding="utf-8",
    )
    output = tmp_path / "frame-gate-audit.json"

    result = _run_cli(
        "audit-frame-gate",
        "--data-root",
        str(data_root),
        "--raw-root",
        str(raw_root),
        "--output",
        str(output),
    )

    assert result.returncode == 0, result.stderr
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["minimum_valid_frame_count"] == 70
    assert report["mismatch_clip_count"] == 2
    assert report["by_split"] == {"train": 1, "valid": 1, "test": 0}
    assert report["by_label"] == {
        "Not-Engaged": 0,
        "Barely-Engaged": 1,
        "Engaged": 1,
        "Highly-Engaged": 0,
    }


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
