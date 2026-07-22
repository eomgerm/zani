from __future__ import annotations

import argparse
import html
import re
import sys
from collections.abc import Iterable
from datetime import datetime
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


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate an EngageNet HTML EDA report")
    parser.add_argument("--data-root", type=Path, default=Path("datasets"))
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


def render_report(records: pd.DataFrame, output: Path) -> None:
    split_summary, class_summary, qa_summary = build_summary_tables(records)
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
.ok-callout{padding:14px 16px;border-radius:10px;background:#ecfdf5;color:#047857}.notes{color:var(--muted);font-size:14px}.notes code{background:#edf1f7;color:#1e3a5f;padding:2px 6px;border-radius:5px}.foot{margin-top:32px;padding-top:18px;border-top:1px solid var(--line);color:var(--muted);font-size:12px}
@media(max-width:980px){.kpis{grid-template-columns:repeat(2,1fr)}.findings{grid-template-columns:repeat(2,1fr)}.chart-grid{grid-template-columns:1fr}}@media(max-width:600px){main{padding:18px 12px 42px}.hero{padding:25px 22px}.hero h1{font-size:27px}.kpis,.findings{grid-template-columns:1fr}}
"""
    document = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>EngageNet 데이터 EDA</title><style>{css}</style><script>{plotly_js}</script></head>
<body><main>
<header class="hero"><h1>EngageNet 데이터 EDA</h1><p>학습 전 데이터 구성, 라벨 균형, 영상 메타데이터, MARLIN 특징 품질을 전수 검사한 리포트입니다.</p><div class="meta">생성: {generated_at} · 원본 프레임 전체 디코딩 없이 컨테이너 헤더와 특징 텐서를 순차 검사</div></header>
<section class="kpis"><article class="kpi"><span>전체 클립</span><strong>{total_clips:,}</strong></article><article class="kpi"><span>고유 Subject</span><strong>{total_subjects:,}</strong></article><article class="kpi"><span>원본 영상</span><strong>{video_gib:.2f} GiB</strong></article><article class="kpi"><span>MARLIN 특징</span><strong>{feature_gib:.2f} GiB</strong></article></section>
<h2>핵심 진단</h2><section class="findings">{finding_html}</section>
<h2>Split 개요</h2><section class="panel">{dataframe_html(split_summary)}</section>
<h2>클래스 분포</h2><section class="panel">{dataframe_html(class_summary)}</section>
<section class="chart-grid">{figure_html}</section>
<h2>데이터 품질 검사</h2><section class="panel">{dataframe_html(qa_summary)}</section>
<h2>Subject split 누수</h2><section class="panel">{leakage_html}</section>
<h2>검토 대상 샘플</h2><section class="panel"><p class="notes">오류와 검토 신호를 합쳐 최대 100건만 표시합니다. 드문 시간 길이나 8~12초 밖 영상은 삭제 기준이 아니라 원본 확인 대상입니다.</p>{flagged_html}</section>
<h2>해석 및 재현 방법</h2><section class="panel notes"><ul><li>라벨은 <code>chunk</code> 파일명으로 영상 및 <code>.mp4.pt</code> 특징과 결합했습니다.</li><li>클래스 비율은 각 split 내부 비율입니다. <code>SNP(Subject Not Present)</code>는 참여도 클래스가 아닌 품질/제외 범주로 별도 표시하며 불균형 배율 계산에서 제외했습니다.</li><li>MARLIN 특징은 모든 파일을 CPU에서 한 번씩 로드해 rank, 시간 길이, 특징 차원, dtype, 비유한값을 확인했습니다.</li><li>영상 길이, FPS, 해상도, 코덱은 OpenCV로 컨테이너 헤더만 읽어 수집했습니다.</li><li>재실행: <code>uv run --extra eda python scripts/generate_engagenet_eda.py</code></li></ul></section>
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
    if not root.is_dir():
        raise FileNotFoundError(f"dataset root does not exist: {root}")
    print(f"Scanning {root}", flush=True)
    records = collect_records(root)
    args.records_output.parent.mkdir(parents=True, exist_ok=True)
    records.to_csv(args.records_output, index=False, encoding="utf-8-sig")
    render_report(records, args.output)
    print(" | ".join(reconcile(records)), flush=True)
    print(f"Records: {args.records_output.resolve()}", flush=True)
    print(f"Report:  {args.output.resolve()}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
