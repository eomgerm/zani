# EngageNet HTML EDA Report Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a reproducible CLI that joins EngageNet labels, videos, and MARLIN tensors, then writes a self-contained Plotly HTML EDA report and an auditable clip-level CSV.

**Architecture:** Keep dataset inspection in `eda.py` and HTML/Plotly presentation in `eda_report.py`. The existing engagement CLI imports EDA dependencies lazily, so validation, extraction, training, and export keep their current optional-dependency behavior. Every large file is processed sequentially; only compact metadata remains in memory.

**Tech Stack:** Python 3.12, pandas 3.x, openpyxl 3.1+, Plotly 6.x, OpenCV 4.13, PyTorch 2.7+, pytest, uv

## Global Constraints

- Do not modify source videos, labels, or MARLIN tensors.
- Join labels, videos, and features by the exact `chunk` filename including `.mp4`.
- Read video container headers without decoding full frame sequences.
- Load MARLIN tensors one at a time on CPU with `weights_only=True`.
- Treat rare durations or temporal lengths as review signals, not automatic deletion rules.
- Write `artifacts/eda/engagenet_eda_report.html` and `artifacts/eda/engagenet_eda_records.csv`.
- The report must be a self-contained HTML file that works without network access.

---

### Task 1: Optional EDA dependencies and label contract

**Files:**
- Modify: `pyproject.toml`
- Modify: `uv.lock`
- Create: `src/zani_ai/engagement/eda.py`
- Create: `tests/engagement/test_eda.py`

**Interfaces:**
- Consumes: split label files under an EngageNet data root.
- Produces: `LABEL_ORDER`, `LabelContractError`, `parse_chunk_name(name: str) -> ChunkIdentity`, and `load_labels(root: Path) -> pandas.DataFrame`.

- [ ] **Step 1: Write failing label and filename tests**

```python
def test_load_labels_normalizes_excel_and_csv(tmp_path: Path) -> None:
    _write_label_files(tmp_path)
    labels = load_labels(tmp_path)
    assert labels.columns.tolist() == ["split", "chunk", "label"]
    assert labels.groupby("split").size().to_dict() == {
        "Train": 2,
        "Validation": 1,
        "Test": 1,
    }


def test_load_labels_rejects_duplicate_chunk(tmp_path: Path) -> None:
    _write_label_files(tmp_path, duplicate_train_chunk=True)
    with pytest.raises(LabelContractError, match="duplicate chunk"):
        load_labels(tmp_path)


def test_parse_chunk_name_extracts_identity() -> None:
    result = parse_chunk_name("subject_55_j489ubvnol_vid_2_17.mp4")
    assert (result.subject, result.token, result.video_index, result.clip_index) == (
        "55", "j489ubvnol", 2, 17
    )
```

- [ ] **Step 2: Run tests and verify the new module is missing**

Run: `uv run --extra eda pytest tests/engagement/test_eda.py -q`

Expected: collection fails because `zani_ai.engagement.eda` does not exist.

- [ ] **Step 3: Add the EDA optional dependency group**

```toml
eda = [
    "opencv-contrib-python>=4.13,<5",
    "openpyxl>=3.1,<4",
    "pandas>=3,<4",
    "plotly>=6,<7",
    "torch>=2.7,<3",
]
```

Run: `uv lock && uv sync --extra eda`

Expected: lock and environment resolve without dependency incompatibilities.

- [ ] **Step 4: Implement label loading and filename parsing**

```python
LABEL_ORDER = ("Not-Engaged", "Barely-engaged", "Engaged", "Highly-Engaged")
SPLIT_LABEL_FILES = {
    "Train": "train_engagement_labels.xlsx",
    "Validation": "validation_engagement_labels.xlsx",
    "Test": "test_engagement_labels.csv",
}


def load_labels(root: Path) -> pd.DataFrame:
    frames: list[pd.DataFrame] = []
    for split, filename in SPLIT_LABEL_FILES.items():
        path = root / filename
        frame = pd.read_csv(path) if path.suffix == ".csv" else pd.read_excel(path)
        missing = {"chunk", "label"} - set(frame.columns)
        if missing:
            raise LabelContractError(f"{filename} missing columns: {sorted(missing)}")
        normalized = frame.loc[:, ["chunk", "label"]].copy()
        normalized.insert(0, "split", split)
        frames.append(normalized)
    labels = pd.concat(frames, ignore_index=True)
    duplicates = labels[labels.duplicated(["split", "chunk"], keep=False)]
    if not duplicates.empty:
        raise LabelContractError("duplicate chunk keys in label files")
    unknown = sorted(set(labels["label"]) - set(LABEL_ORDER))
    if unknown:
        raise LabelContractError(f"unknown labels: {unknown}")
    return labels
```

- [ ] **Step 5: Run focused tests**

Run: `uv run --extra eda pytest tests/engagement/test_eda.py -q`

Expected: all Task 1 tests pass.

- [ ] **Step 6: Commit Task 1**

```powershell
git add pyproject.toml uv.lock src/zani_ai/engagement/eda.py tests/engagement/test_eda.py
git commit -m "✨ feat: EngageNet EDA 데이터 계약 추가 (S15P11A105-151)"
```

---

### Task 2: Sequential video and MARLIN metadata collection

**Files:**
- Modify: `src/zani_ai/engagement/eda.py`
- Modify: `tests/engagement/test_eda.py`

**Interfaces:**
- Consumes: `load_labels(root)`, split video directories, and matching MARLIN directories.
- Produces: `VideoMetadata`, `FeatureMetadata`, `probe_video(path)`, `probe_feature(path)`, and `collect_records(root, *, video_probe=probe_video, feature_probe=probe_feature) -> pandas.DataFrame`.

- [ ] **Step 1: Write failing collection tests with injected probes**

```python
def test_collect_records_joins_by_chunk_and_flags_missing_feature(tmp_path: Path) -> None:
    _write_label_files(tmp_path)
    _touch_split_videos(tmp_path)
    records = collect_records(
        tmp_path,
        video_probe=lambda _: VideoMetadata(True, 30.0, 300, 10.0, 640, 480, "avc1", None),
        feature_probe=lambda path: (
            FeatureMetadata(False, None, None, None, None, "missing")
            if "missing" in path.name
            else FeatureMetadata(True, 9, 1024, "torch.float32", True, None)
        ),
    )
    assert records["chunk"].is_unique
    assert records.loc[records["chunk"].str.contains("missing"), "feature_missing"].all()
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run: `uv run --extra eda pytest tests/engagement/test_eda.py::test_collect_records_joins_by_chunk_and_flags_missing_feature -q`

Expected: FAIL because metadata probes and record collection are not defined.

- [ ] **Step 3: Implement resilient probes**

```python
def probe_video(path: Path) -> VideoMetadata:
    capture = cv2.VideoCapture(str(path))
    try:
        if not capture.isOpened():
            return VideoMetadata(False, None, None, None, None, None, None, "open failed")
        fps = float(capture.get(cv2.CAP_PROP_FPS))
        frames = int(capture.get(cv2.CAP_PROP_FRAME_COUNT))
        width = int(capture.get(cv2.CAP_PROP_FRAME_WIDTH))
        height = int(capture.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fourcc = int(capture.get(cv2.CAP_PROP_FOURCC))
        codec = "".join(chr((fourcc >> 8 * index) & 0xFF) for index in range(4))
        duration = frames / fps if fps > 0 else None
        return VideoMetadata(True, fps, frames, duration, width, height, codec, None)
    finally:
        capture.release()


def probe_feature(path: Path) -> FeatureMetadata:
    if not path.exists():
        return FeatureMetadata(False, None, None, None, None, "missing")
    try:
        tensor = torch.load(path, map_location="cpu", weights_only=True)
        if not isinstance(tensor, torch.Tensor) or tensor.ndim != 2:
            return FeatureMetadata(False, None, None, None, None, "expected rank-2 tensor")
        return FeatureMetadata(
            True, int(tensor.shape[0]), int(tensor.shape[1]), str(tensor.dtype),
            bool(torch.isfinite(tensor).all()), None,
        )
    except Exception as error:
        return FeatureMetadata(False, None, None, None, None, str(error))
```

- [ ] **Step 4: Implement record collection and quality flags**

```python
def collect_records(
    root: Path,
    *,
    video_probe: Callable[[Path], VideoMetadata] = probe_video,
    feature_probe: Callable[[Path], FeatureMetadata] = probe_feature,
) -> pd.DataFrame:
    labels = load_labels(root)
    rows: list[dict[str, object]] = []
    for split in SPLIT_LABEL_FILES:
        video_names = {path.name for path in (root / split).glob("*.mp4")}
        feature_names = {
            path.name.removesuffix(".pt")
            for path in (root / f"MARLIN_{split}").glob("*.mp4.pt")
        }
        label_lookup = labels.loc[labels["split"] == split].set_index("chunk")["label"].to_dict()
        for chunk in sorted(video_names | feature_names | set(label_lookup)):
            identity = parse_chunk_name(chunk)
            video_path = root / split / chunk
            feature_path = root / f"MARLIN_{split}" / f"{chunk}.pt"
            video = video_probe(video_path) if video_path.exists() else VideoMetadata.missing()
            feature = feature_probe(feature_path)
            rows.append(build_record(split, chunk, identity, label_lookup.get(chunk), video, feature))
    return pd.DataFrame.from_records(rows)
```

`build_record` adds `label_missing`, `video_missing`, `video_open_failed`, `feature_missing`,
`feature_load_failed`, `unexpected_feature_dim`, and `nonfinite_feature` boolean columns.

- [ ] **Step 5: Run Task 2 tests and static checks**

Run: `uv run --extra eda pytest tests/engagement/test_eda.py -q`

Run: `uv run ruff check src/zani_ai/engagement/eda.py tests/engagement/test_eda.py`

Expected: all tests and Ruff checks pass.

- [ ] **Step 6: Commit Task 2**

```powershell
git add src/zani_ai/engagement/eda.py tests/engagement/test_eda.py
git commit -m "✨ feat: 영상과 MARLIN 메타데이터 수집 추가 (S15P11A105-151)"
```

---

### Task 3: Self-contained Plotly HTML report

**Files:**
- Create: `src/zani_ai/engagement/eda_report.py`
- Create: `tests/engagement/test_eda_report.py`

**Interfaces:**
- Consumes: clip-level records from `collect_records`.
- Produces: `build_report_html(records: pandas.DataFrame, *, generated_at: datetime) -> str`.

- [ ] **Step 1: Write a failing report contract test**

```python
def test_build_report_html_is_self_contained(sample_records: pd.DataFrame) -> None:
    html = build_report_html(sample_records, generated_at=datetime(2026, 7, 22, tzinfo=UTC))
    assert "EngageNet 데이터 EDA" in html
    assert "클래스 분포" in html
    assert "MARLIN 시간 길이" in html
    assert "plotly.js" in html.lower()
    assert "https://cdn.plot.ly" not in html
    assert sample_records["chunk"].iloc[0] in html
```

- [ ] **Step 2: Run the report test and verify it fails**

Run: `uv run --extra eda pytest tests/engagement/test_eda_report.py -q`

Expected: collection fails because `eda_report.py` does not exist.

- [ ] **Step 3: Implement summary tables and Plotly figures**

Create figures for class counts and percentages, video duration and size distributions, resolution counts, MARLIN temporal-length counts on a logarithmic y-axis, label-by-temporal-length heatmap, and clips-per-subject distribution. Keep split and label color maps fixed across all figures.

- [ ] **Step 4: Implement one-file HTML rendering**

```python
plotly_js = get_plotlyjs()
figure_html = "".join(
    pio.to_html(figure, full_html=False, include_plotlyjs=False, config={"responsive": True})
    for figure in figures
)
return f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>EngageNet 데이터 EDA</title><style>{REPORT_CSS}</style><script>{plotly_js}</script></head>
<body><main>{summary_html}{figure_html}{quality_table_html}{method_html}</main></body></html>"""
```

- [ ] **Step 5: Run report tests and lint**

Run: `uv run --extra eda pytest tests/engagement/test_eda_report.py -q`

Run: `uv run ruff check src/zani_ai/engagement/eda_report.py tests/engagement/test_eda_report.py`

Expected: report tests and Ruff checks pass.

- [ ] **Step 6: Commit Task 3**

```powershell
git add src/zani_ai/engagement/eda_report.py tests/engagement/test_eda_report.py
git commit -m "✨ feat: 인터랙티브 EDA HTML 리포트 추가 (S15P11A105-151)"
```

---

### Task 4: CLI integration, full dataset run, and verification

**Files:**
- Modify: `src/zani_ai/engagement/cli.py`
- Modify: `tests/engagement/test_cli.py`
- Modify: `README.md`
- Generate: `artifacts/eda/engagenet_eda_report.html`
- Generate: `artifacts/eda/engagenet_eda_records.csv`

**Interfaces:**
- Consumes: `collect_records` and `build_report_html`.
- Produces: `python -m zani_ai engagement eda --data-root datasets --output artifacts/eda/engagenet_eda_report.html --records-output artifacts/eda/engagenet_eda_records.csv`.

- [ ] **Step 1: Write a failing CLI delegation test**

```python
def test_eda_command_writes_report_and_records(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("zani_ai.engagement.eda.generate_eda_report", fake_generate)
    code = main(["eda", "--data-root", str(tmp_path), "--output", str(tmp_path / "report.html")])
    assert code == 0
    assert (tmp_path / "report.html").read_text(encoding="utf-8") == "<html></html>"
```

- [ ] **Step 2: Run the CLI test and verify the command is absent**

Run: `uv run --extra eda pytest tests/engagement/test_cli.py::test_eda_command_writes_report_and_records -q`

Expected: FAIL because the `eda` parser is not registered.

- [ ] **Step 3: Add a lazy EDA command handler and documentation**

Register `eda` with `--data-root`, `--output`, and `--records-output`. Import the EDA module inside the command handler so base commands do not require pandas, Plotly, OpenCV, or PyTorch.

- [ ] **Step 4: Run all automated checks**

Run: `uv run --extra eda pytest -q`

Run: `uv run ruff check .`

Run: `uv run mypy src`

Expected: all tests pass, Ruff reports no issues, and mypy reports success.

- [ ] **Step 5: Generate the real report**

Run: `uv run --extra eda python -m zani_ai engagement eda --data-root datasets --output artifacts/eda/engagenet_eda_report.html --records-output artifacts/eda/engagenet_eda_records.csv`

Expected: 11,311 clip records are written; the process reports no subject overlap, no missing labels, no missing MARLIN files, and no non-finite MARLIN tensors.

- [ ] **Step 6: Verify artifact contents and browser rendering**

Check that the CSV row count equals 11,311, HTML contains every required section, no external Plotly CDN URL exists, and all summary totals reconcile to the CSV. Open the HTML in the local browser and verify KPI cards, tables, charts, responsive width, and Korean text rendering.

- [ ] **Step 7: Commit Task 4**

```powershell
git add src/zani_ai/engagement/cli.py tests/engagement/test_cli.py README.md
git commit -m "✨ feat: EngageNet EDA CLI 연결 (S15P11A105-151)"
```
