from __future__ import annotations

import json
import math
from pathlib import Path
from typing import cast

import numpy as np

from zani_ai.engagement.contracts import LABELS, DatasetContract, SplitName
from zani_ai.engagement.extraction import (
    MINIMUM_VALID_FRAMES,
    SEGMENT_COUNT,
    WINDOW_SECONDS,
)
from zani_ai.engagement.raw_cache import RAW_SCHEMA_NAME
from zani_ai.engagement.segments import (
    EXPECTED_FRAME_COUNT,
    MINIMUM_VALID_FRAME_RATIO,
)


def audit_frame_gate_gap(contract: DatasetContract, raw_root: Path) -> dict[str, object]:
    """Count clips accepted by the old segment rule but rejected by runtime coverage."""
    manifest_path = raw_root / "manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"raw manifest not found: {manifest_path}")
    payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or payload.get("schema") != RAW_SCHEMA_NAME:
        raise ValueError(f"raw manifest schema must be {RAW_SCHEMA_NAME}")
    if payload.get("status") != "complete" or payload.get("complete") is not True:
        raise ValueError(f"raw manifest is incomplete (status={payload.get('status')!r})")
    included = payload.get("included")
    if not isinstance(included, list):
        raise ValueError("raw manifest must contain an included list")

    records = {
        (record.split, record.clip_id): record
        for split_records in contract.splits.values()
        for record in split_records
    }
    by_split = {split: 0 for split in ("train", "valid", "test")}
    by_label = {label: 0 for label in LABELS}
    by_split_and_label = {
        split: {label: 0 for label in LABELS} for split in ("train", "valid", "test")
    }
    mismatches: list[dict[str, object]] = []
    segment_ms = WINDOW_SECONDS * 1000 / SEGMENT_COUNT
    minimum_valid_frame_count = math.ceil(EXPECTED_FRAME_COUNT * MINIMUM_VALID_FRAME_RATIO)

    for item in included:
        if not isinstance(item, dict):
            raise ValueError("raw manifest included entries must be objects")
        clip_id = str(item.get("clip_id", ""))
        split = cast(SplitName, item.get("split"))
        key = (split, clip_id)
        if key not in records:
            raise ValueError(
                f"raw manifest clip is absent from dataset contract: {split}/{clip_id}"
            )
        feature_path = raw_root / str(item.get("feature_path", ""))
        with np.load(feature_path, allow_pickle=False) as cache:
            valid_mask = np.asarray(cache["valid_mask"], dtype=np.bool_)
            timestamps_ms = np.asarray(cache["timestamps_ms"])
        if valid_mask.ndim != 1 or timestamps_ms.shape != valid_mask.shape:
            raise ValueError(f"raw cache has invalid validity/timestamp shape: {feature_path}")

        counts = [0] * SEGMENT_COUNT
        for valid, timestamp_ms in zip(valid_mask, timestamps_ms, strict=True):
            timestamp = float(timestamp_ms)
            if not valid or not 0 <= timestamp < WINDOW_SECONDS * 1000:
                continue
            index = min(int(timestamp / segment_ms), SEGMENT_COUNT - 1)
            counts[index] += 1
        valid_frame_count = sum(counts)
        old_rule_passes = all(count >= MINIMUM_VALID_FRAMES for count in counts)
        if not old_rule_passes or valid_frame_count >= minimum_valid_frame_count:
            continue

        record = records[key]
        by_split[split] += 1
        by_label[record.label] += 1
        by_split_and_label[split][record.label] += 1
        mismatches.append(
            {
                "clip_id": clip_id,
                "split": split,
                "label": record.label,
                "valid_frame_count": valid_frame_count,
            }
        )

    return {
        "expected_frame_count": EXPECTED_FRAME_COUNT,
        "minimum_valid_frame_ratio": MINIMUM_VALID_FRAME_RATIO,
        "minimum_valid_frame_count": minimum_valid_frame_count,
        "mismatch_clip_count": len(mismatches),
        "by_split": by_split,
        "by_label": by_label,
        "by_split_and_label": by_split_and_label,
        "mismatch_clips": mismatches,
    }


def write_frame_gate_audit(report: dict[str, object], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


__all__ = ["audit_frame_gate_gap", "write_frame_gate_audit"]
