from __future__ import annotations

import argparse
import hashlib
import html
import json
import platform
import re
import subprocess
import sys
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import datetime
from importlib import metadata
from pathlib import Path
from zoneinfo import ZoneInfo

import cv2
import numpy as np
import pandas as pd
import plotly.express as px
import plotly.graph_objects as go
import plotly.io as pio
import torch
from plotly.offline import get_plotlyjs


SPLITS = ("Train", "Validation", "Test")
LABEL_FILES = {
    "Train": "train_engagement_labels.xlsx",
    "Validation": "validation_engagement_labels.xlsx",
    "Test": "test_engagement_labels.csv",
}
LABEL_ORDER = ("Not-Engaged", "Barely-engaged", "Engaged", "Highly-Engaged")
SNP_LABEL = "SNP(Subject Not Present)"
ALL_LABELS = (*LABEL_ORDER, SNP_LABEL)
OFFICIAL_CLIP_COUNTS = {"Train": 7983, "Validation": 1071, "Test": 2257}
OFFICIAL_SUBJECT_COUNTS = {"Train": 90, "Validation": 11, "Test": 26}
OFFICIAL_TOTAL_CLIPS = 11311
OFFICIAL_TOTAL_SUBJECTS = 127
EXPECTED_FEATURE_SCHEMA = "mediapipe_98_v1"
EXPECTED_TOKEN_SHAPE = (20, 98)
OFFICIAL_TEST_ACCURACY = 67.61
REQUIRED_RECORD_COLUMNS = {
    "split",
    "chunk",
    "label",
    "subject",
    "video_missing",
    "video_opened",
    "feature_missing",
    "feature_loaded",
    "feature_dim",
    "feature_finite",
    "video_bytes",
    "feature_bytes",
    "review_flags",
}
BOOLEAN_RECORD_COLUMNS = (
    "label_missing",
    "filename_valid",
    "video_missing",
    "video_opened",
    "feature_missing",
    "feature_loaded",
    "feature_finite",
    "video_open_failed",
    "feature_load_failed",
    "unexpected_feature_dim",
    "nonfinite_feature",
    "duration_review",
    "fps_review",
    "rare_time_length",
)
NUMERIC_RECORD_COLUMNS = (
    "fps",
    "frame_count",
    "duration_s",
    "width",
    "height",
    "video_bytes",
    "feature_bytes",
    "feature_rank",
    "time_tokens",
    "feature_dim",
)
SPLIT_COLORS = {"Train": "#2563EB", "Validation": "#F59E0B", "Test": "#10B981"}
LABEL_COLORS = {
    "Not-Engaged": "#DC2626",
    "Barely-engaged": "#F59E0B",
    "Engaged": "#22C55E",
    "Highly-Engaged": "#2563EB",
    SNP_LABEL: "#94A3B8",
}
CHUNK_PATTERN = re.compile(
    r"^subject_(?P<subject>[^_]+)_(?P<token>[^_]+)_vid_"
    r"(?P<video_index>\d+)_(?P<clip_index>\d+)\.mp4$"
)


@dataclass(frozen=True, slots=True)
class ManifestAssessment:
    status: str
    summary: str
    table: pd.DataFrame
    error: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate an EngageNet HTML EDA report")
    parser.add_argument("--data-root", type=Path, default=Path("datasets"))
    parser.add_argument(
        "--records-csv",
        type=Path,
        help="Reuse a previously generated records CSV instead of scanning the dataset",
    )
    parser.add_argument(
        "--extraction-manifest",
        type=Path,
        help="Optional MediaPipe extraction manifest.json",
    )
    parser.add_argument(
        "--face-landmarker-model",
        type=Path,
        help="Optional Face Landmarker .task file for provenance hashing",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("artifacts/eda/engagenet_eda_report.html"),
    )
    parser.add_argument(
        "--records-output",
        type=Path,
        default=Path("artifacts/eda/engagenet_eda_records.csv"),
    )
    return parser.parse_args()


def _normalize_boolean(series: pd.Series) -> pd.Series:
    if pd.api.types.is_bool_dtype(series):
        return series.fillna(False).astype(bool)
    normalized = series.astype("string").str.strip().str.casefold()
    return normalized.map(
        {"true": True, "false": False, "1": True, "0": False, "yes": True, "no": False}
    ).fillna(False).astype(bool)


def normalize_record_types(records: pd.DataFrame) -> pd.DataFrame:
    records = records.copy()
    for column in BOOLEAN_RECORD_COLUMNS:
        if column in records:
            records[column] = _normalize_boolean(records[column])
    for column in NUMERIC_RECORD_COLUMNS:
        if column in records:
            records[column] = pd.to_numeric(records[column], errors="coerce")
    records["review_flags"] = records["review_flags"].fillna("").astype(str)
    return records


def load_records_csv(path: Path) -> pd.DataFrame:
    if not path.is_file():
        raise FileNotFoundError(f"records CSV does not exist: {path}")
    records = pd.read_csv(path, encoding="utf-8-sig", low_memory=False)
    missing = sorted(REQUIRED_RECORD_COLUMNS - set(records.columns))
    if missing:
        raise ValueError(f"records CSV is missing required columns: {', '.join(missing)}")
    return normalize_record_types(records)


def load_labels(root: Path) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    for split, filename in LABEL_FILES.items():
        path = root / filename
        if not path.exists():
            raise FileNotFoundError(f"missing label file: {path}")
        frame = pd.read_csv(path) if path.suffix == ".csv" else pd.read_excel(path)
        missing = {"chunk", "label"} - set(frame.columns)
        if missing:
            raise ValueError(f"{path.name} missing columns: {sorted(missing)}")
        normalized = frame.loc[:, ["chunk", "label"]].copy()
        normalized.insert(0, "split", split)
        frames.append(normalized)

    labels = pd.concat(frames, ignore_index=True)
    labels["chunk"] = labels["chunk"].astype(str).str.strip()
    labels["label"] = labels["label"].astype(str).str.strip()
    duplicates = labels[labels.duplicated(["split", "chunk"], keep=False)]
    if not duplicates.empty:
        examples = duplicates[["split", "chunk"]].head(5).to_dict("records")
        raise ValueError(f"duplicate chunk keys in labels: {examples}")
    unknown = sorted(set(labels["label"]) - set(ALL_LABELS))
    if unknown:
        raise ValueError(f"unknown engagement labels: {unknown}")
    return labels


def parse_chunk(chunk: str) -> dict[str, object]:
    match = CHUNK_PATTERN.fullmatch(chunk)
    if match is None:
        return {
            "subject": None,
            "subject_token": None,
            "source_video": None,
            "clip_index": None,
            "filename_valid": False,
        }
    return {
        "subject": match.group("subject"),
        "subject_token": match.group("token"),
        "source_video": int(match.group("video_index")),
        "clip_index": int(match.group("clip_index")),
        "filename_valid": True,
    }


def decode_fourcc(value: int) -> str:
    codec = "".join(chr((value >> (8 * index)) & 0xFF) for index in range(4))
    return codec.strip("\x00 ") or "unknown"


def probe_video(path: Path) -> dict[str, object]:
    if not path.exists():
        return {
            "video_missing": True,
            "video_opened": False,
            "video_error": "missing",
        }
    capture = cv2.VideoCapture(str(path))
    try:
        if not capture.isOpened():
            return {
                "video_missing": False,
                "video_opened": False,
                "video_error": "OpenCV could not open the container",
            }
        fps = float(capture.get(cv2.CAP_PROP_FPS))
        frame_count = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fourcc = int(capture.get(cv2.CAP_PROP_FOURCC))
        duration = frame_count / fps if fps > 0 else np.nan
        return {
            "video_missing": False,
            "video_opened": True,
            "video_error": None,
            "fps": fps,
            "frame_count": frame_count,
            "duration_s": duration,
            "width": width,
            "height": height,
            "resolution": f"{width}×{height}",
            "codec": decode_fourcc(fourcc),
            "video_bytes": path.stat().st_size,
        }
    finally:
        capture.release()


def probe_feature(path: Path) -> dict[str, object]:
    if not path.exists():
        return {
            "feature_missing": True,
            "feature_loaded": False,
            "feature_error": "missing",
        }
    try:
        value = torch.load(path, map_location="cpu", weights_only=True)
        if not isinstance(value, torch.Tensor):
            return {
                "feature_missing": False,
                "feature_loaded": False,
                "feature_error": f"expected tensor, got {type(value).__name__}",
                "feature_bytes": path.stat().st_size,
            }
        shape = tuple(int(size) for size in value.shape)
        finite = bool(torch.isfinite(value).all()) if value.is_floating_point() else True
        return {
            "feature_missing": False,
            "feature_loaded": True,
            "feature_error": None,
            "feature_bytes": path.stat().st_size,
            "feature_rank": value.ndim,
            "time_tokens": shape[0] if len(shape) >= 1 else np.nan,
            "feature_dim": shape[1] if len(shape) >= 2 else np.nan,
            "feature_shape": "×".join(map(str, shape)),
            "feature_dtype": str(value.dtype),
            "feature_finite": finite,
        }
    except Exception as error:  # noqa: BLE001 - an EDA scan must keep inspecting other files
        return {
            "feature_missing": False,
            "feature_loaded": False,
            "feature_error": f"{type(error).__name__}: {error}",
            "feature_bytes": path.stat().st_size,
        }


def collect_records(root: Path) -> pd.DataFrame:
    labels = load_labels(root)
    rows: list[dict[str, object]] = []
    processed = 0
    cv2.setNumThreads(1)

    for split in SPLITS:
        label_lookup = (
            labels.loc[labels["split"] == split, ["chunk", "label"]]
            .set_index("chunk")["label"]
            .to_dict()
        )
        video_paths = {path.name: path for path in (root / split).glob("*.mp4")}
        feature_paths = {
            path.name.removesuffix(".pt"): path
            for path in (root / f"MARLIN_{split}").glob("*.mp4.pt")
        }
        chunks = sorted(set(label_lookup) | set(video_paths) | set(feature_paths))
        print(
            f"[{split}] labels={len(label_lookup):,} videos={len(video_paths):,} "
            f"features={len(feature_paths):,}",
            flush=True,
        )

        for chunk in chunks:
            processed += 1
            video_path = video_paths.get(chunk, root / split / chunk)
            feature_path = feature_paths.get(
                chunk, root / f"MARLIN_{split}" / f"{chunk}.pt"
            )
            row: dict[str, object] = {
                "split": split,
                "chunk": chunk,
                "label": label_lookup.get(chunk),
                "label_missing": chunk not in label_lookup,
                "video_path": str(video_path),
                "feature_path": str(feature_path),
            }
            row.update(parse_chunk(chunk))
            row.update(probe_video(video_path))
            row.update(probe_feature(feature_path))
            rows.append(row)
            if processed % 500 == 0:
                print(f"  inspected {processed:,} clips", flush=True)

    records = pd.DataFrame.from_records(rows)
    bool_defaults = {
        "video_missing": False,
        "video_opened": False,
        "feature_missing": False,
        "feature_loaded": False,
        "feature_finite": False,
        "filename_valid": False,
    }
    for column, default in bool_defaults.items():
        records[column] = records.get(column, default).fillna(default).astype(bool)

    records["video_open_failed"] = ~records["video_missing"] & ~records["video_opened"]
    records["feature_load_failed"] = ~records["feature_missing"] & ~records["feature_loaded"]
    records["unexpected_feature_dim"] = (
        records["feature_loaded"] & records["feature_dim"].ne(1024)
    )
    records["nonfinite_feature"] = records["feature_loaded"] & ~records["feature_finite"]
    records["duration_review"] = records["video_opened"] & (
        records["duration_s"].lt(8) | records["duration_s"].gt(12)
    )
    records["fps_review"] = records["video_opened"] & (
        records["fps"].lt(5) | records["fps"].gt(120)
    )

    time_counts = records.loc[records["feature_loaded"], "time_tokens"].value_counts()
    rare_lengths = set(time_counts[time_counts < 10].index)
    records["rare_time_length"] = records["time_tokens"].isin(rare_lengths)

    flag_columns = [
        "label_missing",
        "video_missing",
        "video_open_failed",
        "feature_missing",
        "feature_load_failed",
        "unexpected_feature_dim",
        "nonfinite_feature",
        "duration_review",
        "fps_review",
        "rare_time_length",
    ]
    records["review_flags"] = records.apply(
        lambda row: ", ".join(column for column in flag_columns if bool(row[column])), axis=1
    )
    return records


def percent(value: float) -> str:
    return f"{value:.1%}"


def dataframe_html(frame: pd.DataFrame, *, classes: str = "data-table") -> str:
    return frame.to_html(index=False, border=0, classes=classes, escape=True)


def make_figures(records: pd.DataFrame) -> list[tuple[str, go.Figure]]:
    plot = records.copy()
    plot["split"] = pd.Categorical(plot["split"], SPLITS, ordered=True)
    plot["label"] = pd.Categorical(plot["label"], ALL_LABELS, ordered=True)

    class_counts = (
        plot.dropna(subset=["label"])
        .groupby(["split", "label"], observed=False)
        .size()
        .rename("count")
        .reset_index()
    )
    class_counts["share"] = class_counts.groupby("split", observed=False)["count"].transform(
        lambda values: values / values.sum()
    )
    count_fig = px.bar(
        class_counts,
        x="label",
        y="count",
        color="split",
        barmode="group",
        color_discrete_map=SPLIT_COLORS,
        category_orders={"label": list(ALL_LABELS), "split": list(SPLITS)},
        labels={"label": "참여도 라벨", "count": "클립 수", "split": "Split"},
    )
    count_fig.update_layout(title="클래스별 클립 수")

    share_fig = px.bar(
        class_counts,
        x="label",
        y="share",
        color="split",
        barmode="group",
        color_discrete_map=SPLIT_COLORS,
        category_orders={"label": list(ALL_LABELS), "split": list(SPLITS)},
        labels={"label": "참여도 라벨", "share": "Split 내 비율", "split": "Split"},
    )
    share_fig.update_layout(title="Split별 클래스 비율", yaxis_tickformat=".0%")

    duration_fig = px.box(
        plot.loc[plot["video_opened"]],
        x="split",
        y="duration_s",
        color="split",
        color_discrete_map=SPLIT_COLORS,
        points="outliers",
        labels={"split": "Split", "duration_s": "영상 길이 (초)"},
    )
    duration_fig.update_layout(title="영상 길이 분포", showlegend=False)

    size_plot = plot.loc[plot["video_opened"], ["split", "video_bytes"]].copy()
    size_plot["video_size_mib"] = size_plot["video_bytes"] / 2**20
    size_fig = px.box(
        size_plot,
        x="split",
        y="video_size_mib",
        color="split",
        color_discrete_map=SPLIT_COLORS,
        points="outliers",
        labels={"split": "Split", "video_size_mib": "파일 크기 (MiB)"},
    )
    size_fig.update_layout(title="영상 파일 크기 분포", showlegend=False)

    resolution_counts = (
        plot.loc[plot["video_opened"], ["split", "resolution"]]
        .groupby(["resolution", "split"], observed=False)
        .size()
        .rename("count")
        .reset_index()
    )
    top_resolutions = (
        resolution_counts.groupby("resolution", observed=False)["count"]
        .sum()
        .nlargest(12)
        .index
    )
    resolution_counts = resolution_counts[resolution_counts["resolution"].isin(top_resolutions)]
    resolution_fig = px.bar(
        resolution_counts,
        x="resolution",
        y="count",
        color="split",
        barmode="stack",
        color_discrete_map=SPLIT_COLORS,
        labels={"resolution": "해상도", "count": "클립 수", "split": "Split"},
    )
    resolution_fig.update_layout(title="주요 영상 해상도")

    fps_counts = (
        plot.loc[plot["video_opened"], ["split", "fps"]]
        .assign(fps_rounded=lambda frame: frame["fps"].round(3))
        .groupby(["fps_rounded", "split"], observed=False)
        .size()
        .rename("count")
        .reset_index()
    )
    top_fps = (
        fps_counts.groupby("fps_rounded", observed=False)["count"]
        .sum()
        .nlargest(15)
        .index
    )
    fps_counts = fps_counts[fps_counts["fps_rounded"].isin(top_fps)].copy()
    fps_counts["fps_label"] = fps_counts["fps_rounded"].map(lambda value: f"{value:g}")
    fps_fig = px.bar(
        fps_counts,
        x="fps_label",
        y="count",
        color="split",
        barmode="group",
        color_discrete_map=SPLIT_COLORS,
        labels={"fps_label": "FPS", "count": "클립 수", "split": "Split"},
    )
    fps_fig.update_layout(title="주요 영상 FPS 값")

    time_counts = (
        plot.loc[plot["feature_loaded"]]
        .groupby(["time_tokens", "split"], observed=False)
        .size()
        .rename("count")
        .reset_index()
        .sort_values("time_tokens")
    )
    time_counts["time_tokens_label"] = time_counts["time_tokens"].astype("Int64").astype(str)
    time_fig = px.bar(
        time_counts,
        x="time_tokens_label",
        y="count",
        color="split",
        barmode="group",
        color_discrete_map=SPLIT_COLORS,
        labels={"time_tokens_label": "시간 토큰 수", "count": "파일 수", "split": "Split"},
    )
    time_fig.update_layout(title="MARLIN 시간 길이 분포 (로그 축)", yaxis_type="log")

    heat = pd.crosstab(plot["label"], plot["time_tokens"], normalize="index")
    heat = heat.reindex(index=ALL_LABELS, fill_value=0).sort_index(axis=1)
    heat_fig = go.Figure(
        data=go.Heatmap(
            z=heat.to_numpy(),
            x=[str(int(value)) for value in heat.columns],
            y=list(heat.index),
            colorscale="Blues",
            colorbar={"title": "라벨 내 비율"},
            hovertemplate="라벨=%{y}<br>시간 토큰=%{x}<br>비율=%{z:.2%}<extra></extra>",
        )
    )
    heat_fig.update_layout(
        title="라벨별 MARLIN 시간 길이 구성",
        xaxis_title="시간 토큰 수",
        yaxis_title="참여도 라벨",
    )

    time_fps_source = plot.loc[plot["feature_loaded"] & plot["video_opened"]].copy()
    time_fps_source["fps_rounded"] = time_fps_source["fps"].round(3)
    top_time_values = time_fps_source["time_tokens"].value_counts().nlargest(12).index
    top_fps_values = time_fps_source["fps_rounded"].value_counts().nlargest(12).index
    time_fps_table = pd.crosstab(
        time_fps_source["time_tokens"], time_fps_source["fps_rounded"]
    ).reindex(index=top_time_values, columns=top_fps_values, fill_value=0)
    time_fps_fig = go.Figure(
        data=go.Heatmap(
            z=np.log10(time_fps_table.to_numpy() + 1),
            x=[f"{value:g}" for value in time_fps_table.columns],
            y=[str(int(value)) for value in time_fps_table.index],
            customdata=time_fps_table.to_numpy(),
            colorscale="Viridis",
            colorbar={"title": "log10(건수+1)"},
            hovertemplate="FPS=%{x}<br>시간 토큰=%{y}<br>클립=%{customdata:,}<extra></extra>",
        )
    )
    time_fps_fig.update_layout(
        title="주요 FPS × MARLIN 시간 길이",
        xaxis_title="FPS",
        yaxis_title="시간 토큰 수",
    )

    subject_counts = (
        plot.dropna(subset=["subject"])
        .groupby(["split", "subject"], observed=False)
        .size()
        .rename("clips")
        .reset_index()
    )
    subject_fig = px.box(
        subject_counts,
        x="split",
        y="clips",
        color="split",
        color_discrete_map=SPLIT_COLORS,
        points="all",
        labels={"split": "Split", "clips": "Subject당 클립 수"},
    )
    subject_fig.update_layout(title="Subject별 클립 수 분포", showlegend=False)

    figures = [
        ("클래스 분포", count_fig),
        ("클래스 비율", share_fig),
        ("영상 길이", duration_fig),
        ("영상 크기", size_fig),
        ("영상 해상도", resolution_fig),
        ("영상 FPS", fps_fig),
        ("MARLIN 시간 길이", time_fig),
        ("라벨-시간 길이", heat_fig),
        ("FPS-시간 길이", time_fps_fig),
        ("Subject 균형", subject_fig),
    ]
    for _, figure in figures:
        figure.update_layout(
            template="plotly_white",
            height=430,
            margin={"l": 65, "r": 28, "t": 72, "b": 70},
            font={"family": "Pretendard, Noto Sans KR, Segoe UI, sans-serif", "size": 12},
            legend={"orientation": "h", "yanchor": "bottom", "y": 1.02, "x": 0},
        )
    return figures


def build_summary_tables(records: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    summary_rows: list[dict[str, object]] = []
    for split in SPLITS:
        group = records[records["split"] == split]
        opened = group[group["video_opened"]]
        loaded = group[group["feature_loaded"]]
        summary_rows.append(
            {
                "Split": split,
                "클립": len(group),
                "Subject": group["subject"].nunique(),
                "라벨 매칭": percent(group["label"].notna().mean()),
                "영상 열기": percent(group["video_opened"].mean()),
                "MARLIN 로드": percent(group["feature_loaded"].mean()),
                "영상 GiB": f"{group['video_bytes'].sum() / 2**30:.2f}",
                "특징 GiB": f"{group['feature_bytes'].sum() / 2**30:.2f}",
                "길이 중앙값(초)": f"{opened['duration_s'].median():.2f}",
                "시간 토큰 중앙값": f"{loaded['time_tokens'].median():.0f}",
            }
        )
    split_summary = pd.DataFrame(summary_rows)

    counts = pd.crosstab(records["label"], records["split"]).reindex(
        index=ALL_LABELS, columns=SPLITS, fill_value=0
    )
    counts["전체"] = counts.sum(axis=1)
    shares = counts.loc[:, SPLITS].div(counts.loc[:, SPLITS].sum(axis=0), axis=1)
    class_rows: list[dict[str, object]] = []
    for label in ALL_LABELS:
        class_rows.append(
            {
                "라벨": label,
                **{
                    split: f"{counts.at[label, split]:,} ({shares.at[label, split]:.1%})"
                    for split in SPLITS
                },
                "전체": f"{counts.at[label, '전체']:,}",
            }
        )
    class_summary = pd.DataFrame(class_rows)

    qa_rows = [
        ("라벨 누락", int(records["label_missing"].sum()), "0이어야 함"),
        ("영상 파일 누락", int(records["video_missing"].sum()), "0이어야 함"),
        ("영상 헤더 열기 실패", int(records["video_open_failed"].sum()), "0이어야 함"),
        ("MARLIN 파일 누락", int(records["feature_missing"].sum()), "0이어야 함"),
        ("MARLIN 로드 실패", int(records["feature_load_failed"].sum()), "0이어야 함"),
        ("특징 차원 != 1024", int(records["unexpected_feature_dim"].sum()), "0이어야 함"),
        ("비유한 특징값", int(records["nonfinite_feature"].sum()), "0이어야 함"),
        ("SNP 품질 라벨", int(records["label"].eq(SNP_LABEL).sum()), "학습 제외 검토"),
        ("8~12초 밖 영상", int(records["duration_review"].sum()), "검토 신호"),
        ("FPS < 5 또는 > 120", int(records["fps_review"].sum()), "검토 신호"),
        ("빈도 10 미만 시간 길이", int(records["rare_time_length"].sum()), "검토 신호"),
    ]
    qa_summary = pd.DataFrame(qa_rows, columns=["검사", "건수", "판정 기준"])
    qa_summary["상태"] = np.where(
        (qa_summary["판정 기준"] == "0이어야 함") & (qa_summary["건수"] > 0),
        "오류",
        np.where(qa_summary["건수"] > 0, "검토", "정상"),
    )
    return split_summary, class_summary, qa_summary


def subject_leakage(records: pd.DataFrame) -> pd.DataFrame:
    return (
        records.dropna(subset=["subject"])
        .groupby("subject")["split"]
        .agg(lambda values: ", ".join(sorted(set(values))))
        .loc[lambda values: values.str.contains(",")]
        .rename("splits")
        .reset_index()
    )


def build_findings(records: pd.DataFrame) -> list[tuple[str, str, str]]:
    label_coverage = records["label"].notna().mean()
    feature_coverage = records["feature_loaded"].mean()
    leakage = subject_leakage(records)
    label_counts = records["label"].value_counts().reindex(LABEL_ORDER)
    dominant = str(label_counts.idxmax())
    imbalance = float(label_counts.max() / label_counts.min())
    time_counts = records.loc[records["feature_loaded"], "time_tokens"].value_counts()
    fps_counts = records.loc[records["video_opened"], "fps"].round(3).value_counts()
    modal_text = ", ".join(
        f"{int(length)}토큰 {int(count):,}개" for length, count in time_counts.head(3).items()
    )
    return [
        (
            "good" if label_coverage == 1 else "bad",
            "라벨 매칭",
            f"{percent(label_coverage)} · {records['label'].notna().sum():,}/{len(records):,} 클립",
        ),
        (
            "good" if feature_coverage == 1 else "bad",
            "MARLIN 매칭",
            f"{percent(feature_coverage)} · 비유한 파일 {records['nonfinite_feature'].sum():,}개",
        ),
        (
            "good" if leakage.empty else "bad",
            "Subject split 누수",
            "없음" if leakage.empty else f"{len(leakage):,}명 중복",
        ),
        (
            "warn" if imbalance >= 1.5 else "good",
            "클래스 불균형",
            f"최대/최소 {imbalance:.2f}배 · 최다 {dominant}",
        ),
        (
            "warn" if len(time_counts) > 2 else "good",
            "MARLIN 시간 길이",
            f"{len(time_counts):,}종 · 상위: {modal_text}",
        ),
        (
            "warn" if len(fps_counts) > 2 else "good",
            "영상 FPS 변형",
            f"{len(fps_counts):,}종 · 상위: "
            + ", ".join(f"{fps:g}fps {count:,}개" for fps, count in fps_counts.head(3).items()),
        ),
    ]


def _comparison_row(
    item: str,
    expected: int | str,
    observed: int | str,
    *,
    explanation: str,
    informational: bool = False,
) -> dict[str, object]:
    if informational:
        status = "확인"
    else:
        status = "통과" if expected == observed else "차단"
    return {
        "항목": item,
        "기준": f"{expected:,}" if isinstance(expected, int) else expected,
        "관측": f"{observed:,}" if isinstance(observed, int) else observed,
        "상태": status,
        "설명": explanation,
    }


def build_protocol_table(records: pd.DataFrame) -> pd.DataFrame:
    rows = [
        _comparison_row(
            "전체 클립",
            OFFICIAL_TOTAL_CLIPS,
            len(records),
            explanation="SNP를 포함한 공개 원본 기준",
        )
    ]
    for split in SPLITS:
        group = records[records["split"] == split]
        rows.append(
            _comparison_row(
                f"{split} 클립",
                OFFICIAL_CLIP_COUNTS[split],
                len(group),
                explanation="공식 subject-independent split 원본 수",
            )
        )
    rows.append(
        _comparison_row(
            "전체 Subject",
            OFFICIAL_TOTAL_SUBJECTS,
            int(records["subject"].nunique()),
            explanation="파일명에서 파싱한 subject ID 기준",
        )
    )
    for split in SPLITS:
        group = records[records["split"] == split]
        rows.append(
            _comparison_row(
                f"{split} Subject",
                OFFICIAL_SUBJECT_COUNTS[split],
                int(group["subject"].nunique()),
                explanation="파일명에서 파싱한 subject ID 기준",
            )
        )
    training_records = records[records["label"].isin(LABEL_ORDER)]
    rows.extend(
        [
            _comparison_row(
                "4-class 학습 대상",
                "SNP 제외",
                len(training_records),
                explanation="네 참여도 라벨만 포함한 파생 학습 대상",
                informational=True,
            ),
            _comparison_row(
                "SNP 품질 범주",
                "학습 제외",
                int(records["label"].eq(SNP_LABEL).sum()),
                explanation="공식 베이스라인처럼 분류 target에서 제외해야 함",
                informational=True,
            ),
        ]
    )
    return pd.DataFrame(rows)


def build_training_distribution(records: pd.DataFrame) -> pd.DataFrame:
    training = records[records["label"].isin(LABEL_ORDER)].copy()
    counts = (
        training.groupby(["split", "label"], observed=False)
        .size()
        .rename("클립")
        .reset_index()
    )
    split_totals = training.groupby("split").size()
    counts["Split 내 비율"] = counts.apply(
        lambda row: percent(row["클립"] / split_totals.get(row["split"], 1)), axis=1
    )
    counts["공식 분류 target"] = "포함"
    counts["split"] = pd.Categorical(counts["split"], categories=SPLITS, ordered=True)
    counts["label"] = pd.Categorical(counts["label"], categories=LABEL_ORDER, ordered=True)
    counts = counts.sort_values(["split", "label"])
    counts = counts.rename(columns={"split": "Split", "label": "라벨"})
    return counts.reset_index(drop=True)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as file:
        for block in iter(lambda: file.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _normalize_clip_id(value: object) -> str:
    return Path(str(value).strip()).stem


def _display_split(value: object) -> str:
    normalized = str(value).strip().casefold()
    return {"train": "Train", "valid": "Validation", "validation": "Validation", "test": "Test"}.get(
        normalized, str(value)
    )


def _exclusion_category(reason: object) -> str:
    text = str(reason).strip()
    if text.startswith("segment ") and "valid frames" in text:
        return "구간별 얼굴 검출 부족"
    if "invalid FPS or frame count" in text:
        return "영상 FPS/프레임 수 오류"
    if "did not return a face transform" in text:
        return "얼굴 변환 행렬 누락"
    return text[:100] or "사유 미기록"


def inspect_extraction_manifest(
    path: Path | None, records: pd.DataFrame
) -> ManifestAssessment:
    if path is None:
        table = pd.DataFrame(
            [
                ("추출 manifest", "제공", "미지정", "미실행"),
                ("특징 schema", EXPECTED_FEATURE_SCHEMA, "미확인", "미검증"),
                ("token shape", str(list(EXPECTED_TOKEN_SHAPE)), "미확인", "미검증"),
            ],
            columns=["항목", "기준", "관측", "상태"],
        )
        return ManifestAssessment(
            "미실행",
            "MediaPipe 추출 manifest가 없어 실제 [20, 98] 모델 입력은 아직 검증되지 않았습니다.",
            table,
        )

    resolved = path.resolve()
    try:
        if not resolved.is_file():
            raise FileNotFoundError(f"manifest does not exist: {resolved}")
        payload = json.loads(resolved.read_text(encoding="utf-8"))
        if not isinstance(payload, dict):
            raise ValueError("manifest root must be a JSON object")
        included = payload.get("included")
        excluded = payload.get("excluded")
        if not isinstance(included, list) or not isinstance(excluded, list):
            raise ValueError("manifest requires 'included' and 'excluded' arrays")
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as error:
        table = pd.DataFrame(
            [("추출 manifest", "유효한 JSON", str(resolved), "검증 실패")],
            columns=["항목", "기준", "관측", "상태"],
        )
        return ManifestAssessment("검증 실패", "MediaPipe manifest를 해석하지 못했습니다.", table, str(error))

    schema = str(payload.get("schema", ""))
    rows: list[dict[str, object]] = [
        {
            "항목": "특징 schema",
            "기준": EXPECTED_FEATURE_SCHEMA,
            "관측": schema or "미기록",
            "상태": "통과" if schema == EXPECTED_FEATURE_SCHEMA else "검증 실패",
        }
    ]
    shape_value = payload.get("token_shape", payload.get("feature_shape"))
    observed_shape: tuple[int, ...] | None = None
    if isinstance(shape_value, list):
        try:
            observed_shape = tuple(int(value) for value in shape_value)
        except (TypeError, ValueError):
            observed_shape = None
    rows.append(
        {
            "항목": "token shape",
            "기준": str(list(EXPECTED_TOKEN_SHAPE)),
            "관측": str(list(observed_shape)) if observed_shape else "manifest에 미기록",
            "상태": (
                "통과"
                if observed_shape == EXPECTED_TOKEN_SHAPE
                else "검증 실패"
                if observed_shape is not None
                else "미검증"
            ),
        }
    )

    manifest_rows: list[dict[str, object]] = []
    for entry in included:
        if isinstance(entry, dict):
            manifest_rows.append(
                {
                    "clip_id": _normalize_clip_id(entry.get("clip_id", "")),
                    "split": _display_split(entry.get("split", "")),
                    "result": "포함",
                    "reason": "",
                    "feature_path": str(entry.get("feature_path", "")),
                }
            )
    for entry in excluded:
        if isinstance(entry, dict):
            manifest_rows.append(
                {
                    "clip_id": _normalize_clip_id(entry.get("clip_id", "")),
                    "split": _display_split(entry.get("split", "")),
                    "result": "제외",
                    "reason": _exclusion_category(entry.get("reason", "")),
                    "feature_path": "",
                }
            )
    manifest_frame = pd.DataFrame(
        manifest_rows, columns=["clip_id", "split", "result", "reason", "feature_path"]
    )
    for split in (*SPLITS, "전체"):
        group = manifest_frame if split == "전체" else manifest_frame[manifest_frame["split"] == split]
        included_count = int(group["result"].eq("포함").sum()) if not group.empty else 0
        excluded_count = int(group["result"].eq("제외").sum()) if not group.empty else 0
        total = included_count + excluded_count
        rows.append(
            {
                "항목": f"{split} 추출" if split != "전체" else "전체 추출",
                "기준": "제외율 ≤ 5%" if split == "전체" else "포함/제외 기록",
                "관측": f"포함 {included_count:,} · 제외 {excluded_count:,} ({excluded_count / total:.1%})" if total else "0개",
                "상태": "통과" if total and (split != "전체" or excluded_count / total <= 0.05) else "확인",
            }
        )

    if not manifest_frame.empty:
        record_labels = records[["chunk", "label"]].copy()
        record_labels["clip_id"] = record_labels["chunk"].map(_normalize_clip_id)
        excluded_frame = manifest_frame[manifest_frame["result"] == "제외"].merge(
            record_labels[["clip_id", "label"]], on="clip_id", how="left"
        )
        for reason, count in excluded_frame["reason"].value_counts().items():
            rows.append(
                {"항목": f"제외 사유: {reason}", "기준": "검토", "관측": f"{count:,}개", "상태": "확인"}
            )
        for label, count in excluded_frame["label"].fillna("라벨 미매칭").value_counts().items():
            rows.append(
                {"항목": f"제외 영향: {label}", "기준": "검토", "관측": f"{count:,}개", "상태": "확인"}
            )

    table = pd.DataFrame(rows)
    failed = table["상태"].eq("검증 실패").any()
    unverified = table["상태"].eq("미검증").any()
    status = "검증 실패" if failed else "미검증" if unverified else "검증 완료"
    summary = (
        "MediaPipe 특징 계약 검증에 실패한 항목이 있습니다."
        if failed
        else "manifest에는 token shape가 없어 실제 [20, 98] 배열 검증이 남았습니다."
        if unverified
        else "MediaPipe manifest의 schema와 token shape가 학습 계약과 일치합니다."
    )
    return ManifestAssessment(status, summary, table)


def _package_version(package: str) -> str:
    try:
        return metadata.version(package)
    except metadata.PackageNotFoundError:
        return "미설치/확인 불가"


def _git_commit() -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=Path(__file__).resolve().parents[2],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "확인 불가"


def collect_provenance(
    *,
    records_source: Path | None,
    data_root: Path,
    manifest_path: Path | None,
    face_landmarker_model: Path | None,
) -> pd.DataFrame:
    rows: list[dict[str, str]] = []

    def add(item: str, value: str, status: str = "확인") -> None:
        rows.append({"항목": item, "값": value, "상태": status})

    if records_source and records_source.is_file():
        stat = records_source.stat()
        add("레코드 CSV", str(records_source))
        add("레코드 CSV 크기", f"{stat.st_size:,} bytes")
        add(
            "레코드 CSV 수정 시각",
            datetime.fromtimestamp(stat.st_mtime, ZoneInfo("Asia/Seoul")).isoformat(),
        )
        add("레코드 CSV SHA-256", sha256_file(records_source))
    else:
        add("레코드 CSV", "원본 데이터에서 이번 실행에 수집", "확인")
    add("데이터 root", str(data_root), "확인" if data_root.is_dir() else "경고")
    add("Git commit", _git_commit())
    add("Python", platform.python_version())
    add("PyTorch", torch.__version__)
    mediapipe_version = _package_version("mediapipe")
    add(
        "MediaPipe",
        mediapipe_version,
        "미검증" if mediapipe_version.startswith("미설치") else "확인",
    )
    add("특징 schema", EXPECTED_FEATURE_SCHEMA)
    add("샘플링 FPS", "10")
    add("분석 window", "10초")
    add("temporal segments", str(EXPECTED_TOKEN_SHAPE[0]))
    add("token 차원", str(EXPECTED_TOKEN_SHAPE[1]))
    add("구간당 최소 유효 프레임", "3")
    add("논문 비교 정확도", f"{OFFICIAL_TEST_ACCURACY:.2f}% (OpenFace Gaze+HP+AU Transformer)")

    if manifest_path and manifest_path.resolve().is_file():
        resolved_manifest = manifest_path.resolve()
        add("추출 manifest", str(resolved_manifest))
        add("추출 manifest SHA-256", sha256_file(resolved_manifest))
    else:
        add("추출 manifest", "미지정", "미검증")

    if face_landmarker_model and face_landmarker_model.resolve().is_file():
        resolved_model = face_landmarker_model.resolve()
        add("Face Landmarker 모델", str(resolved_model))
        add("Face Landmarker SHA-256", sha256_file(resolved_model))
    else:
        add("Face Landmarker 모델", "미지정", "미검증")

    metadata_files = [data_root / filename for filename in LABEL_FILES.values()]
    metadata_files.extend(data_root / name for name in ("final_labels.csv", "train.txt", "valid.txt", "test.txt"))
    for path in metadata_files:
        if path.is_file():
            add(f"메타데이터 SHA-256 · {path.name}", sha256_file(path))
    return pd.DataFrame(rows)


def readiness_verdict(
    protocol_table: pd.DataFrame, manifest: ManifestAssessment
) -> tuple[str, str, str]:
    if protocol_table["상태"].eq("차단").any():
        return "차단", "공식 subject protocol 불일치를 해결해야 합니다.", "blocked"
    if manifest.status == "검증 실패":
        return "차단", "MediaPipe 추출 manifest 검증에 실패했습니다.", "blocked"
    if manifest.status in {"미실행", "미검증"}:
        return "조건부 준비", "원본은 준비됐지만 MediaPipe 모델 입력은 미검증입니다.", "unverified"
    return "준비 완료", "공식 데이터 조건과 MediaPipe 입력 조건을 충족했습니다.", "pass"


def render_report(
    records: pd.DataFrame,
    output: Path,
    *,
    source_mode: str,
    records_source: Path | None,
    data_root: Path,
    manifest: ManifestAssessment,
    manifest_path: Path | None,
    face_landmarker_model: Path | None,
) -> None:
    split_summary, class_summary, qa_summary = build_summary_tables(records)
    protocol_table = build_protocol_table(records)
    training_distribution = build_training_distribution(records)
    provenance = collect_provenance(
        records_source=records_source,
        data_root=data_root,
        manifest_path=manifest_path,
        face_landmarker_model=face_landmarker_model,
    )
    verdict, verdict_detail, verdict_tone = readiness_verdict(protocol_table, manifest)
    leakage = subject_leakage(records)
    figures = make_figures(records)
    findings = build_findings(records)
    flagged = records.loc[records["review_flags"].ne("")].copy()
    flagged = flagged.sort_values(["review_flags", "split", "chunk"]).head(100)
    flagged_columns = [
        "split",
        "chunk",
        "label",
        "duration_s",
        "resolution",
        "time_tokens",
        "feature_dim",
        "review_flags",
    ]
    flagged_view = flagged.loc[:, flagged_columns].copy()
    if not flagged_view.empty:
        flagged_view["duration_s"] = flagged_view["duration_s"].map(
            lambda value: "" if pd.isna(value) else f"{value:.2f}"
        )

    total_clips = len(records)
    total_subjects = records["subject"].nunique()
    video_gib = records["video_bytes"].sum() / 2**30
    feature_gib = records["feature_bytes"].sum() / 2**30
    generated_at = datetime.now(ZoneInfo("Asia/Seoul")).strftime("%Y-%m-%d %H:%M:%S KST")
    plotly_js = get_plotlyjs()
    figure_html = "".join(
        f"<article class='chart-card'><h3>{html.escape(title)}</h3>"
        + pio.to_html(
            figure,
            full_html=False,
            include_plotlyjs=False,
            config={"responsive": True, "displaylogo": False, "scrollZoom": False},
            div_id=f"chart-{index}",
        )
        + "</article>"
        for index, (title, figure) in enumerate(figures)
    )
    finding_html = "".join(
        f"<article class='finding {tone}'><span>{html.escape(title)}</span>"
        f"<strong>{html.escape(detail)}</strong></article>"
        for tone, title, detail in findings
    )
    leakage_html = (
        "<p class='ok-callout'>Train/Validation/Test 사이에 중복된 subject가 없습니다.</p>"
        if leakage.empty
        else dataframe_html(leakage)
    )
    flagged_html = (
        dataframe_html(flagged_view)
        if not flagged_view.empty
        else "<p class='ok-callout'>검토 플래그가 지정된 클립이 없습니다.</p>"
    )
    manifest_error_html = (
        f"<p class='error-callout'>{html.escape(manifest.error)}</p>" if manifest.error else ""
    )
    manifest_tone = {
        "검증 완료": "verified",
        "검증 실패": "failed",
        "미실행": "unverified",
        "미검증": "unverified",
    }.get(manifest.status, "informational")
    manifest_html = (
        f"<p class='status-line'><span class='status-chip {manifest_tone}'>"
        f"{html.escape(manifest.status)}</span>{html.escape(manifest.summary)}</p>"
        f"{manifest_error_html}{dataframe_html(manifest.table)}"
    )
    records_source_text = str(records_source) if records_source else "이번 실행에서 원본 수집"

    css = """
:root{--ink:#14213d;--muted:#62708a;--line:#dfe5ee;--blue:#2563eb;--bg:#f5f7fb;--card:#fff;--good:#0f9f6e;--warn:#d97706;--bad:#dc2626}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:Pretendard,"Noto Sans KR","Segoe UI",sans-serif;line-height:1.55}
main{max-width:1440px;margin:0 auto;padding:36px 28px 72px}.hero{background:linear-gradient(135deg,#0f274f,#214fa1);color:#fff;border-radius:22px;padding:34px 38px;box-shadow:0 18px 42px rgba(15,39,79,.18)}
.hero h1{margin:0 0 8px;font-size:34px;letter-spacing:-.03em}.hero p{margin:0;color:#dce8ff}.meta{margin-top:16px;font-size:13px;color:#bdd0f5}
.kpis{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;margin:20px 0}.kpi,.finding,.panel,.chart-card{background:var(--card);border:1px solid var(--line);border-radius:16px;box-shadow:0 8px 22px rgba(20,33,61,.05)}
.kpi{padding:20px}.kpi span{color:var(--muted);font-size:13px}.kpi strong{display:block;font-size:28px;margin-top:5px;letter-spacing:-.03em}
h2{font-size:23px;margin:38px 0 14px;letter-spacing:-.02em}h3{font-size:16px;margin:0 0 8px}.findings{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.finding{padding:16px;border-top:4px solid var(--good)}
.finding.warn{border-top-color:var(--warn)}.finding.bad{border-top-color:var(--bad)}.finding span{font-size:12px;color:var(--muted)}.finding strong{display:block;margin-top:4px;font-size:14px}
.panel{padding:22px;margin-bottom:18px;overflow:auto}.chart-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.chart-card{padding:18px;min-width:0}.chart-card h3{color:var(--muted);font-weight:600}
table.data-table{border-collapse:collapse;width:100%;font-size:13px;white-space:nowrap}table.data-table th{background:#eef3fb;color:#35435f;text-align:left;padding:10px 12px;border-bottom:2px solid #cbd5e1}table.data-table td{padding:9px 12px;border-bottom:1px solid #edf0f5}table.data-table tr:hover td{background:#f8faff}
.readiness{border-radius:18px;padding:24px 26px;color:#fff;box-shadow:0 10px 28px rgba(20,33,61,.12)}.readiness.pass{background:#087f5b}.readiness.blocked{background:#b91c1c}.readiness.unverified{background:#9a6700}.readiness span{display:block;font-size:13px;opacity:.85}.readiness strong{display:block;font-size:30px;margin:2px 0 4px}.readiness p{margin:0}.status-line{display:flex;align-items:center;gap:10px}.status-chip{display:inline-flex;padding:3px 9px;border-radius:999px;font-size:12px;font-weight:700;background:#e2e8f0}.status-chip.verified{background:#d1fae5;color:#047857}.status-chip.unverified{background:#fef3c7;color:#92400e}.status-chip.failed{background:#fee2e2;color:#b91c1c}
.ok-callout{padding:14px 16px;border-radius:10px;background:#ecfdf5;color:#047857}.error-callout{padding:14px 16px;border-radius:10px;background:#fef2f2;color:#b91c1c}.notes{color:var(--muted);font-size:14px}.notes code{background:#edf1f7;color:#1e3a5f;padding:2px 6px;border-radius:5px}.foot{margin-top:32px;padding-top:18px;border-top:1px solid var(--line);color:var(--muted);font-size:12px}
@media(max-width:980px){.kpis{grid-template-columns:repeat(2,1fr)}.findings{grid-template-columns:repeat(2,1fr)}.chart-grid{grid-template-columns:1fr}}@media(max-width:600px){main{padding:18px 12px 42px}.hero{padding:25px 22px}.hero h1{font-size:27px}.kpis,.findings{grid-template-columns:1fr}}
"""
    document = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>EngageNet EDA · 모델 재현 준비도</title><style>{css}</style><script>{plotly_js}</script></head>
<body><main>
<header class="hero"><h1>EngageNet EDA · 모델 재현 준비도</h1><p>원본 데이터와 MARLIN 인벤토리뿐 아니라 공식 split, 4-class 학습 대상, MediaPipe 입력 검증 상태를 구분해 보여줍니다.</p><div class="meta">생성: {generated_at} · {html.escape(source_mode)} · 레코드: {html.escape(records_source_text)} · 원본 데이터는 수정하지 않음</div></header>
<section class="kpis"><article class="kpi"><span>전체 클립</span><strong>{total_clips:,}</strong></article><article class="kpi"><span>고유 Subject</span><strong>{total_subjects:,}</strong></article><article class="kpi"><span>원본 영상</span><strong>{video_gib:.2f} GiB</strong></article><article class="kpi"><span>MARLIN 특징</span><strong>{feature_gib:.2f} GiB</strong></article></section>
<h2>모델 재현 준비도</h2><section class="readiness {verdict_tone}"><span>종합 판정</span><strong>{html.escape(verdict)}</strong><p>{html.escape(verdict_detail)}</p></section>
<h2>공식 프로토콜 비교</h2><section class="panel"><p class="notes">EngageNet 논문 및 공식 저장소의 원본 clip·subject split 기준과 비교합니다. Subject 불일치는 임의 보정하지 않습니다.</p>{dataframe_html(protocol_table)}</section>
<h2>SNP 제외 후 학습 대상</h2><section class="panel"><p class="notes"><code>{html.escape(SNP_LABEL)}</code>는 분류 target이 아니므로 제외한 뒤 네 참여도 클래스만 집계했습니다.</p>{dataframe_html(training_distribution)}</section>
<h2>MediaPipe 특징 준비도</h2><section class="panel">{manifest_html}</section>
<h2>재현성 Provenance</h2><section class="panel">{dataframe_html(provenance)}</section>
<h2>핵심 진단</h2><section class="findings">{finding_html}</section>
<h2>Split 개요</h2><section class="panel">{dataframe_html(split_summary)}</section>
<h2>클래스 분포</h2><section class="panel">{dataframe_html(class_summary)}</section>
<section class="chart-grid">{figure_html}</section>
<h2>데이터 품질 검사</h2><section class="panel">{dataframe_html(qa_summary)}</section>
<h2>Subject split 누수</h2><section class="panel">{leakage_html}</section>
<h2>검토 대상 샘플</h2><section class="panel"><p class="notes">오류와 검토 신호를 합쳐 최대 100건만 표시합니다. 드문 시간 길이나 8~12초 밖 영상은 삭제 기준이 아니라 원본 확인 대상입니다.</p>{flagged_html}</section>
<h2>해석 및 재현 방법</h2><section class="panel notes"><ul><li>라벨은 <code>chunk</code> 파일명으로 영상 및 <code>.mp4.pt</code> 특징과 결합했습니다.</li><li><code>SNP(Subject Not Present)</code>는 품질 범주이며 공식 베이스라인과 동일하게 4-class 학습 대상에서 제외했습니다.</li><li>MARLIN 정상 여부는 MediaPipe <code>[20, 98]</code> 입력 정상 여부를 대신하지 않습니다. 추출 manifest가 없으면 모델 입력은 미검증입니다.</li><li>기존 CSV 재사용 모드에서는 33GB 원본을 다시 순회하지 않습니다. 영상·MARLIN 품질 수치는 CSV가 생성될 당시의 전수 검사 결과입니다.</li><li>원본 스캔: <code>uv run --extra eda python scripts/generate_engagenet_eda.py</code></li><li>CSV 재사용: <code>uv run --extra eda python scripts/generate_engagenet_eda.py --records-csv &lt;records.csv&gt;</code></li></ul></section>
<footer class="foot">ZANI AI · 로컬 EngageNet EDA · 원본 데이터는 수정하지 않았습니다.</footer>
</main></body></html>"""
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(document, encoding="utf-8")


def reconcile(records: pd.DataFrame) -> list[str]:
    messages = [f"records={len(records):,}"]
    messages.append(f"labels={records['label'].notna().sum():,}")
    messages.append(f"videos_opened={records['video_opened'].sum():,}")
    messages.append(f"features_loaded={records['feature_loaded'].sum():,}")
    messages.append(f"subjects={records['subject'].nunique():,}")
    messages.append(f"subject_leakage={len(subject_leakage(records)):,}")
    return messages


def main() -> int:
    args = parse_args()
    root = args.data_root.resolve()
    records_source: Path | None = None
    if args.records_csv is not None:
        records_source = args.records_csv.resolve()
        print(f"Reusing records CSV {records_source}", flush=True)
        records = load_records_csv(records_source)
        source_mode = "기존 레코드 CSV 재사용"
    else:
        if not root.is_dir():
            raise FileNotFoundError(f"dataset root does not exist: {root}")
        print(f"Scanning {root}", flush=True)
        records = normalize_record_types(collect_records(root))
        source_mode = "원본 데이터 스캔"

    manifest_path = (
        args.extraction_manifest.resolve() if args.extraction_manifest is not None else None
    )
    face_landmarker_model = (
        args.face_landmarker_model.resolve() if args.face_landmarker_model is not None else None
    )
    manifest = inspect_extraction_manifest(manifest_path, records)
    args.records_output.parent.mkdir(parents=True, exist_ok=True)
    records.to_csv(args.records_output, index=False, encoding="utf-8-sig")
    render_report(
        records,
        args.output,
        source_mode=source_mode,
        records_source=records_source,
        data_root=root,
        manifest=manifest,
        manifest_path=manifest_path,
        face_landmarker_model=face_landmarker_model,
    )
    print(" | ".join(reconcile(records)), flush=True)
    print(f"Readiness manifest: {manifest.status}", flush=True)
    print(f"Records: {args.records_output.resolve()}", flush=True)
    print(f"Report:  {args.output.resolve()}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
