from __future__ import annotations

import csv
from pathlib import Path

import pytest

from zani_ai.engagement.contracts import DatasetContractError, load_dataset_contract


def _write_labels(root: Path, rows: list[dict[str, str]]) -> None:
    with (root / "final_labels.csv").open("w", newline="", encoding="utf-8") as file:
        writer = csv.DictWriter(file, fieldnames=["clip_id", "label", "subject_id"])
        writer.writeheader()
        writer.writerows(rows)


def _make_root(tmp_path: Path) -> Path:
    (tmp_path / "videos").mkdir()
    for split in ("train", "valid", "test"):
        (tmp_path / f"{split}.txt").write_text(f"{split}_clip\n", encoding="utf-8")
        (tmp_path / "videos" / f"{split}_clip.mp4").touch()
    _write_labels(
        tmp_path,
        [
            {"clip_id": "train_clip", "label": "Barely-engaged", "subject_id": "s1"},
            {"clip_id": "valid_clip", "label": "Engaged", "subject_id": "s2"},
            {"clip_id": "test_clip", "label": "Highly-Engaged", "subject_id": "s3"},
        ],
    )
    return tmp_path


def test_load_contract_normalizes_official_barely_engaged_spelling(tmp_path: Path) -> None:
    contract = load_dataset_contract(_make_root(tmp_path))

    assert contract.splits["train"][0].label == "Barely-Engaged"
    assert contract.splits["train"][0].label_index == 1


def test_load_contract_reports_missing_videos_and_split_overlap(tmp_path: Path) -> None:
    root = _make_root(tmp_path)
    (root / "test.txt").write_text("train_clip\nmissing_clip\n", encoding="utf-8")

    with pytest.raises(DatasetContractError) as error:
        load_dataset_contract(root)

    message = str(error.value)
    assert "present in multiple splits" in message
    assert "missing video" in message


def test_load_contract_rejects_subject_overlap_when_subject_ids_exist(tmp_path: Path) -> None:
    root = _make_root(tmp_path)
    _write_labels(
        root,
        [
            {"clip_id": "train_clip", "label": "Not-Engaged", "subject_id": "same"},
            {"clip_id": "valid_clip", "label": "Engaged", "subject_id": "same"},
            {"clip_id": "test_clip", "label": "Highly-Engaged", "subject_id": "other"},
        ],
    )

    with pytest.raises(DatasetContractError, match="subject same is present"):
        load_dataset_contract(root)


def test_load_contract_lists_all_missing_required_paths(tmp_path: Path) -> None:
    with pytest.raises(DatasetContractError) as error:
        load_dataset_contract(tmp_path)

    message = str(error.value)
    assert "final_labels.csv" in message
    assert "train.txt" in message
    assert "videos" in message
