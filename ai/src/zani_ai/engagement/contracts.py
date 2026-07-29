from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path
from types import MappingProxyType
from typing import Literal

type SplitName = Literal["train", "valid", "test"]

LABELS = ("Not-Engaged", "Barely-Engaged", "Engaged", "Highly-Engaged")

#: Per-class loss weighting schemes. ``balanced`` is sklearn's
#: ``class_weight="balanced"``; ``sqrt_balanced`` softens it for datasets where
#: full inversion overcorrects. Lives here rather than in ``training`` so the
#: CLI can list the choices without importing torch.
CLASS_WEIGHTING_SCHEMES = ("none", "balanced", "sqrt_balanced")

#: Classification loss shapes for the softmax head. ``focal`` adds Lin et al.'s
#: ``(1 - p_t)^gamma`` modulating term, which reweights by *sample difficulty*
#: where ``CLASS_WEIGHTING_SCHEMES`` reweights by class frequency; the two
#: compose. Kept beside the weighting schemes so the CLI can list both without
#: importing torch.
LOSS_SCHEMES = ("cross_entropy", "focal")

#: Training-split sampling schemes. ``balanced`` draws with replacement so each
#: class contributes equally per epoch, which corrects the imbalance in the
#: batch composition rather than in the loss.
SAMPLER_SCHEMES = ("none", "balanced")

#: Target encodings for the softmax head. ``one_hot`` is the usual hard target;
#: ``sord`` (Diaz & Marathe, CVPR 2019) spreads probability over the neighbouring
#: grades by squared grade distance, which leaves the head and the argmax
#: decoding untouched and changes only what the loss is asked to match. Kept
#: beside the other scheme tuples so the CLI can list them without importing
#: torch.
TARGET_ENCODINGS = ("one_hot", "sord")

_LABEL_LOOKUP = {label.casefold(): label for label in LABELS}
_LABEL_LOOKUP["barely-engaged"] = "Barely-Engaged"
_SPLIT_FILES: dict[SplitName, str] = {
    "train": "train.txt",
    "valid": "valid.txt",
    "test": "test.txt",
}


class DatasetContractError(ValueError):
    """Raised when an EngageNet directory violates the expected data contract."""

    def __init__(self, problems: list[str]) -> None:
        self.problems = tuple(problems)
        super().__init__("Invalid EngageNet dataset:\n- " + "\n- ".join(problems))


@dataclass(frozen=True, slots=True)
class ClipRecord:
    clip_id: str
    label: str
    label_index: int
    split: SplitName
    video_path: Path
    subject_id: str | None = None


@dataclass(frozen=True, slots=True)
class DatasetContract:
    root: Path
    splits: dict[SplitName, tuple[ClipRecord, ...]]


@dataclass(frozen=True, slots=True)
class _LabelRow:
    label: str
    subject_id: str | None


def _normalize_clip_id(value: str) -> str:
    return Path(value.strip()).stem


def _read_split(path: Path) -> list[str]:
    lines = path.read_text(encoding="utf-8").splitlines()
    return [_normalize_clip_id(line) for line in lines if line.strip()]


def _read_labels(
    path: Path,
    id_column: str,
    label_column: str,
    subject_column: str | None,
) -> tuple[dict[str, _LabelRow], list[str]]:
    problems: list[str] = []
    result: dict[str, _LabelRow] = {}
    with path.open(newline="", encoding="utf-8-sig") as file:
        reader = csv.DictReader(file)
        fieldnames = reader.fieldnames or []
        actual_id_column = (
            id_column if id_column in fieldnames else (fieldnames[0] if fieldnames else "")
        )
        if not actual_id_column or label_column not in fieldnames:
            return {}, [f"final_labels.csv requires an ID column and '{label_column}' column"]
        for row_number, row in enumerate(reader, start=2):
            clip_id = _normalize_clip_id(row.get(actual_id_column, ""))
            raw_label = row.get(label_column, "").strip()
            label = _LABEL_LOOKUP.get(raw_label.casefold())
            if not clip_id:
                problems.append(f"final_labels.csv row {row_number} has an empty clip ID")
                continue
            if label is None:
                problems.append(f"clip {clip_id} has unknown label '{raw_label}'")
                continue
            if clip_id in result:
                problems.append(f"clip {clip_id} appears more than once in final_labels.csv")
                continue
            subject_value = (
                row.get(subject_column, "").strip()
                if subject_column and subject_column in row
                else ""
            )
            result[clip_id] = _LabelRow(label, subject_value or None)
    return result, problems


def _validate_split_ids(
    split_ids: dict[SplitName, list[str]],
    labels: dict[str, _LabelRow],
    videos_dir: Path,
    video_extension: str,
    *,
    require_videos: bool,
) -> list[str]:
    problems: list[str] = []
    clip_splits: dict[str, list[SplitName]] = {}
    subject_splits: dict[str, set[SplitName]] = {}
    for split, clip_ids in split_ids.items():
        if not clip_ids:
            problems.append(f"{_SPLIT_FILES[split]} is empty")
        for clip_id in clip_ids:
            clip_splits.setdefault(clip_id, []).append(split)
            label = labels.get(clip_id)
            if label is None:
                problems.append(f"clip {clip_id} has no label")
            elif label.subject_id:
                subject_splits.setdefault(label.subject_id, set()).add(split)
            if require_videos and not (
                videos_dir / f"{clip_id}{video_extension}"
            ).is_file():
                problems.append(f"missing video: videos/{clip_id}{video_extension}")
    for clip_id, splits in clip_splits.items():
        if len(splits) > 1 and len(set(splits)) == 1:
            problems.append(f"clip {clip_id} appears more than once in {splits[0]}.txt")
        elif len(set(splits)) > 1:
            problems.append(f"clip {clip_id} is present in multiple splits: {', '.join(splits)}")
    for subject_id, subject_split_names in subject_splits.items():
        if len(subject_split_names) > 1:
            names = ", ".join(sorted(subject_split_names))
            problems.append(f"subject {subject_id} is present in multiple splits: {names}")
    return problems


def load_dataset_contract(
    root: Path,
    *,
    id_column: str = "clip_id",
    label_column: str = "label",
    subject_column: str | None = "subject_id",
    video_extension: str = ".mp4",
    require_videos: bool = True,
) -> DatasetContract:
    """Validate and load an official-style EngageNet dataset directory."""
    root = root.resolve()
    extension = video_extension if video_extension.startswith(".") else f".{video_extension}"
    required = ["final_labels.csv", "train.txt", "valid.txt", "test.txt"]
    if require_videos:
        required.append("videos")
    missing = [name for name in required if not (root / name).exists()]
    if missing:
        raise DatasetContractError([f"missing required path: {name}" for name in missing])

    labels, problems = _read_labels(
        root / "final_labels.csv", id_column, label_column, subject_column
    )
    split_ids = {split: _read_split(root / filename) for split, filename in _SPLIT_FILES.items()}
    problems.extend(
        _validate_split_ids(
            split_ids,
            labels,
            root / "videos",
            extension,
            require_videos=require_videos,
        )
    )
    if problems:
        raise DatasetContractError(problems)

    splits: dict[SplitName, tuple[ClipRecord, ...]] = {}
    for split, clip_ids in split_ids.items():
        records = []
        for clip_id in clip_ids:
            label_row = labels[clip_id]
            records.append(
                ClipRecord(
                    clip_id=clip_id,
                    label=label_row.label,
                    label_index=LABELS.index(label_row.label),
                    split=split,
                    video_path=root / "videos" / f"{clip_id}{extension}",
                    subject_id=label_row.subject_id,
                )
            )
        splits[split] = tuple(records)
    return DatasetContract(root=root, splits=dict(MappingProxyType(splits)))


__all__ = [
    "LABELS",
    "ClipRecord",
    "DatasetContract",
    "DatasetContractError",
    "SplitName",
    "load_dataset_contract",
]
