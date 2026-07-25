# EngageNet MediaPipe Reproduction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a dataset-ready PyTorch reproduction of the EngageNet Gaze + Head Pose + AU Transformer with a 98-dimensional MediaPipe feature contract, ONNX export, and a browser-only real-time inference page.

**Architecture:** Python validates the official-style split files, extracts versioned MediaPipe features into 20 temporal tokens, trains and evaluates a Transformer, then exports the classifier and its metadata. A Vite/TypeScript app independently implements the same feature contract, maintains a rolling 10-second window, and runs the exported model with ONNX Runtime Web without uploading camera frames.

**Tech Stack:** Python 3.12, NumPy, MediaPipe Tasks Vision, OpenCV, PyTorch, scikit-learn, ONNX, ONNX Runtime, pytest, mypy, Ruff, TypeScript, Vite, Vitest, MediaPipe Tasks Vision for Web, ONNX Runtime Web.

## Global Constraints

- Use the official-style `final_labels.csv`, `train.txt`, `valid.txt`, `test.txt`, and `videos/<clip-id>.mp4` data contract.
- Do not create sample videos, synthetic training data, fake checkpoints, or placeholder ONNX models.
- Use the `mediapipe_98_v1` feature order defined in `docs/superpowers/specs/2026-07-21-engagenet-mediapipe-design.md`.
- Sample 10 frames per second, aggregate 20 half-second segments, and require at least 3 valid frames per segment.
- Fail preprocessing if more than 5% of listed clips are excluded.
- Preserve the class order `Not-Engaged`, `Barely-Engaged`, `Engaged`, `Highly-Engaged`.
- Keep webcam frames and extracted features inside the browser.
- Do not claim EngageNet accuracy until the real dataset is available.
- Use Jira key `S15P11A105-151` in every commit.

---

### Task 1: Dependencies and official-style dataset contract

**Files:**
- Modify: `pyproject.toml`
- Create: `src/zani_ai/engagement/__init__.py`
- Create: `src/zani_ai/engagement/contracts.py`
- Create: `tests/engagement/test_contracts.py`

**Interfaces:**
- Consumes: an EngageNet root path and optional CSV column/extension overrides.
- Produces: `LABELS`, `ClipRecord`, `DatasetContract`, and `load_dataset_contract(root: Path, *, id_column: str, label_column: str, subject_column: str | None, video_extension: str) -> DatasetContract`.

- [ ] **Step 1: Write failing contract tests**

```python
def test_load_contract_normalizes_official_barely_engaged_spelling(tmp_path: Path) -> None:
    root = write_contract_files(tmp_path, label="Barely-engaged")
    contract = load_dataset_contract(root)
    assert contract.splits["train"][0].label == "Barely-Engaged"


def test_load_contract_reports_missing_videos_and_split_overlap(tmp_path: Path) -> None:
    root = write_invalid_contract_files(tmp_path)
    with pytest.raises(DatasetContractError) as error:
        load_dataset_contract(root)
    assert "missing video" in str(error.value)
    assert "present in multiple splits" in str(error.value)
```

- [ ] **Step 2: Run the tests and verify the expected import failure**

Run: `uv run pytest tests/engagement/test_contracts.py -v`

Expected: FAIL because `zani_ai.engagement.contracts` does not exist.

- [ ] **Step 3: Add runtime dependencies and the contract implementation**

Add `opencv-python-headless` to the `vision` extra and `onnx` plus `onnxruntime` to the `train` extra. Implement immutable records and aggregate validation:

```python
LABELS = ("Not-Engaged", "Barely-Engaged", "Engaged", "Highly-Engaged")
LABEL_ALIASES = {"Barely-engaged": "Barely-Engaged"}

@dataclass(frozen=True, slots=True)
class ClipRecord:
    clip_id: str
    label: str
    label_index: int
    split: Literal["train", "valid", "test"]
    video_path: Path
    subject_id: str | None = None

@dataclass(frozen=True, slots=True)
class DatasetContract:
    root: Path
    splits: Mapping[str, tuple[ClipRecord, ...]]

def load_dataset_contract(
    root: Path,
    *,
    id_column: str = "clip_id",
    label_column: str = "label",
    subject_column: str | None = "subject_id",
    video_extension: str = ".mp4",
) -> DatasetContract:
    required = ("final_labels.csv", "train.txt", "valid.txt", "test.txt", "videos")
    missing = [name for name in required if not (root / name).exists()]
    if missing:
        raise DatasetContractError([f"missing required path: {name}" for name in missing])
    labels = _read_labels(root / "final_labels.csv", id_column, label_column, subject_column)
    split_ids = {name: _read_ids(root / filename) for name, filename in SPLIT_FILES.items()}
    problems = _validate_split_ids(split_ids, labels, root / "videos", video_extension)
    if problems:
        raise DatasetContractError(problems)
    return _build_contract(root, labels, split_ids, video_extension)
```

Implement `_read_labels`, `_read_ids`, `_validate_split_ids`, and `_build_contract` in the same module using the standard library `csv` module. Accept a filename-like first CSV column as a fallback clip ID, normalize extensions before matching, reject unknown labels, empty splits, overlap, and missing videos, and check subject overlap when the column exists.

- [ ] **Step 4: Run focused and project checks**

Run: `uv run pytest tests/engagement/test_contracts.py -v`

Expected: PASS.

Run: `uv run ruff check src/zani_ai/engagement tests/engagement && uv run mypy src`

Expected: PASS with no warnings.

- [ ] **Step 5: Commit**

```powershell
git add pyproject.toml uv.lock src/zani_ai/engagement tests/engagement/test_contracts.py
git commit -m "✨ feat: EngageNet 데이터 계약 검증 추가 (S15P11A105-151)"
```

### Task 2: Versioned 98-dimensional MediaPipe feature contract

**Files:**
- Create: `src/zani_ai/engagement/features.py`
- Create: `src/zani_ai/engagement/segments.py`
- Create: `tests/engagement/test_features.py`
- Create: `tests/engagement/test_segments.py`

**Interfaces:**
- Consumes: 478 normalized landmarks, a 4×4 face transform, and a blendshape name-to-score mapping.
- Produces: `extract_frame_features(landmarks, transform, blendshapes) -> NDArray[float32]` with shape `(49,)` and `aggregate_segments(frames, window_seconds, segment_count, minimum_valid_frames) -> NDArray[float32]` with shape `(20, 98)`.

- [ ] **Step 1: Write failing feature-order and geometry tests**

```python
def test_extract_frame_features_has_stable_49_value_order() -> None:
    landmarks, matrix, blendshapes = known_landmarker_output()
    features = extract_frame_features(landmarks, matrix, blendshapes)
    assert features.shape == (49,)
    np.testing.assert_allclose(features[:8], EXPECTED_GAZE, atol=1e-6)
    np.testing.assert_allclose(features[8:14], EXPECTED_HEAD_POSE, atol=1e-6)
    np.testing.assert_allclose(features[14:], EXPECTED_BLENDSHAPES, atol=1e-6)


def test_aggregate_segments_returns_mean_then_population_std() -> None:
    frames = five_frames_per_segment_with_known_values()
    tokens = aggregate_segments(frames, window_seconds=10.0, segment_count=20)
    assert tokens.shape == (20, 98)
    np.testing.assert_allclose(tokens[0, :49], frames[:5].mean(axis=0))
    np.testing.assert_allclose(tokens[0, 49:], frames[:5].std(axis=0, ddof=0))
```

- [ ] **Step 2: Verify RED**

Run: `uv run pytest tests/engagement/test_features.py tests/engagement/test_segments.py -v`

Expected: FAIL because the extraction and aggregation functions are missing.

- [ ] **Step 3: Implement exact feature math and schema metadata**

```python
SCHEMA_NAME = "mediapipe_98_v1"
RAW_FEATURE_COUNT = 49
TOKEN_FEATURE_COUNT = 98
BLENDSHAPE_NAMES = (
    "browDownLeft", "browDownRight", "browInnerUp", "browOuterUpLeft",
    "browOuterUpRight", "cheekPuff", "cheekSquintLeft", "cheekSquintRight",
    "eyeBlinkLeft", "eyeBlinkRight", "eyeLookDownLeft", "eyeLookDownRight",
    "eyeLookInLeft", "eyeLookInRight", "eyeLookOutLeft", "eyeLookOutRight",
    "eyeLookUpLeft", "eyeLookUpRight", "eyeSquintLeft", "eyeSquintRight",
    "eyeWideLeft", "eyeWideRight", "jawOpen", "mouthClose", "mouthFrownLeft",
    "mouthFrownRight", "mouthFunnel", "mouthPucker", "mouthSmileLeft",
    "mouthSmileRight", "mouthStretchLeft", "mouthStretchRight",
    "mouthUpperUpLeft", "mouthUpperUpRight", "noseSneerLeft",
)

@dataclass(frozen=True, slots=True)
class TimedFeatures:
    timestamp_seconds: float
    values: NDArray[np.float32] | None

def extract_frame_features(
    landmarks: NDArray[np.float32],
    transform: NDArray[np.float32],
    blendshapes: Mapping[str, float],
) -> NDArray[np.float32]:
    right_iris = landmarks[468:473, :2].mean(axis=0)
    left_iris = landmarks[473:478, :2].mean(axis=0)
    right_xy = _normalized_eye_position(right_iris, landmarks, 33, 133, 159, 145)
    left_xy = _normalized_eye_position(left_iris, landmarks, 263, 362, 386, 374)
    gaze = np.concatenate((right_xy, left_xy, (right_xy + left_xy) / 2, right_xy - left_xy))
    yaw, pitch, roll = _matrix_to_euler_xyz(transform[:3, :3])
    interocular = max(float(np.linalg.norm(landmarks[33, :2] - landmarks[263, :2])), 1e-6)
    head = np.array((yaw, pitch, roll, landmarks[1, 0], landmarks[1, 1], 1 / interocular))
    face = np.array([blendshapes.get(name, 0.0) for name in BLENDSHAPE_NAMES])
    result = np.concatenate((gaze, head, face)).astype(np.float32)
    if result.shape != (RAW_FEATURE_COUNT,) or not np.isfinite(result).all():
        raise InvalidFrameFeaturesError("expected 49 finite MediaPipe features")
    return result

def aggregate_segments(
    frames: Sequence[TimedFeatures],
    *,
    window_seconds: float = 10.0,
    segment_count: int = 20,
    minimum_valid_frames: int = 3,
) -> NDArray[np.float32]:
    segment_seconds = window_seconds / segment_count
    buckets = [[] for _ in range(segment_count)]
    for frame in frames:
        index = min(int(frame.timestamp_seconds / segment_seconds), segment_count - 1)
        if frame.values is not None and 0 <= index < segment_count:
            buckets[index].append(frame.values)
    if any(len(bucket) < minimum_valid_frames for bucket in buckets):
        raise InsufficientFaceCoverageError("every segment requires three valid frames")
    tokens = [np.concatenate((np.mean(bucket, axis=0), np.std(bucket, axis=0))) for bucket in buckets]
    return np.asarray(tokens, dtype=np.float32)
```

Use iris indices `468:473` and `473:478`; eye corners `33/133` and `263/362`; eyelids `159/145` and `386/374`; nose `1`. Convert the rotation matrix to yaw/pitch/roll radians with a documented XYZ convention. Clamp gaze denominators and inter-ocular distance with `1e-6`; reject non-finite output.

- [ ] **Step 4: Verify GREEN and refactor shared constants**

Run: `uv run pytest tests/engagement/test_features.py tests/engagement/test_segments.py -v`

Expected: PASS, including rejection of a segment with only two valid frames.

Run: `uv run pytest && uv run ruff check . && uv run mypy src`

Expected: all existing and new checks PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/zani_ai/engagement tests/engagement
git commit -m "✨ feat: MediaPipe 98차원 특징 계약 구현 (S15P11A105-151)"
```

### Task 3: Offline video feature extraction and cache manifest

**Files:**
- Create: `src/zani_ai/engagement/extraction.py`
- Create: `tests/engagement/test_extraction.py`
- Modify: `.gitignore`
- Modify: `datasets/README.md`

**Interfaces:**
- Consumes: `DatasetContract`, a MediaPipe `face_landmarker.task` path, output root, and failure threshold.
- Produces: per-clip compressed NPZ files and `ExtractionManifest` JSON; `extract_contract(contract, landmarker, output_root, max_excluded_fraction) -> ExtractionManifest`.

- [ ] **Step 1: Write failing orchestration tests using an injected landmarker**

```python
def test_extract_contract_writes_tokens_and_manifest(tmp_path: Path) -> None:
    contract = one_clip_contract(tmp_path)
    manifest = extract_contract(contract, FakeLandmarker(valid_frames=100), tmp_path / "out")
    cached = np.load(manifest.included[0].feature_path)
    assert cached["tokens"].shape == (20, 98)
    assert manifest.schema == "mediapipe_98_v1"


def test_extract_contract_fails_above_exclusion_threshold(tmp_path: Path) -> None:
    with pytest.raises(ExtractionThresholdError):
        extract_contract(many_clip_contract(tmp_path), AlwaysMissingFace(), tmp_path / "out")
```

- [ ] **Step 2: Verify RED**

Run: `uv run pytest tests/engagement/test_extraction.py -v`

Expected: FAIL because `extraction.py` is absent.

- [ ] **Step 3: Implement timestamp sampling and MediaPipe adapter**

```python
class FrameLandmarker(Protocol):
    def detect(self, rgb_frame: NDArray[np.uint8], timestamp_ms: int) -> FrameResult | None:
        """Return one face result, or None when no face is detected."""

def iter_sampled_frames(video_path: Path, sample_fps: float = 10.0) -> Iterator[VideoFrame]:
    capture = cv2.VideoCapture(str(video_path))
    duration_ms = capture.get(cv2.CAP_PROP_FRAME_COUNT) / capture.get(cv2.CAP_PROP_FPS) * 1000
    for timestamp_ms in np.arange(0.0, min(duration_ms, 10_000.0), 1000 / sample_fps):
        capture.set(cv2.CAP_PROP_POS_MSEC, float(timestamp_ms))
        ok, bgr = capture.read()
        if ok:
            yield VideoFrame(int(timestamp_ms), cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
    capture.release()

def extract_clip(
    video_path: Path,
    landmarker: FrameLandmarker,
    *,
    frame_source: Callable[[Path], Iterator[VideoFrame]] = iter_sampled_frames,
) -> NDArray[np.float32]:
    timed = []
    for frame in frame_source(video_path):
        result = landmarker.detect(frame.rgb, frame.timestamp_ms)
        values = None if result is None else extract_frame_features(
            result.landmarks, result.transform, result.blendshapes
        )
        timed.append(TimedFeatures(frame.timestamp_ms / 1000, values))
    return aggregate_segments(timed)

def extract_contract(
    contract: DatasetContract,
    landmarker: FrameLandmarker,
    output_root: Path,
    *,
    max_excluded_fraction: float = 0.05,
    frame_source: Callable[[Path], Iterator[VideoFrame]] = iter_sampled_frames,
) -> ExtractionManifest:
    included, excluded = [], []
    records = tuple(record for split in contract.splits.values() for record in split)
    for record in records:
        try:
            tokens = extract_clip(record.video_path, landmarker, frame_source=frame_source)
            feature_path = _atomic_save_tokens(output_root, record, tokens)
            included.append(IncludedClip.from_record(record, feature_path))
        except (VideoDecodeError, InsufficientFaceCoverageError, InvalidFrameFeaturesError) as error:
            excluded.append(ExcludedClip.from_record(record, str(error)))
    manifest = ExtractionManifest(SCHEMA_NAME, tuple(included), tuple(excluded))
    _atomic_write_manifest(output_root / "manifest.json", manifest)
    if records and len(excluded) / len(records) > max_excluded_fraction:
        raise ExtractionThresholdError(manifest)
    return manifest
```

Update `extract_clip` to accept the same keyword-only `frame_source` argument, defaulting to `iter_sampled_frames`; tests inject an in-memory frame iterator and never create a sample video. The real adapter creates `FaceLandmarkerOptions` with blendshapes and facial transformation matrices enabled. Write cache files atomically, skip only caches whose schema and source fingerprint match, and record included/excluded clips with reasons. Ignore `datasets/processed/**`, `artifacts/**`, model weights, and MediaPipe `.task` assets.

- [ ] **Step 4: Verify GREEN**

Run: `uv run pytest tests/engagement/test_extraction.py -v`

Expected: PASS without requiring an installed webcam or real video.

Run: `uv run pytest && uv run ruff check . && uv run mypy src`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add .gitignore datasets/README.md src/zani_ai/engagement/extraction.py tests/engagement/test_extraction.py
git commit -m "✨ feat: EngageNet 영상 특징 추출 파이프라인 추가 (S15P11A105-151)"
```

### Task 4: Paper-based Transformer and training/evaluation pipeline

**Files:**
- Create: `src/zani_ai/engagement/model.py`
- Create: `src/zani_ai/engagement/training.py`
- Create: `tests/engagement/test_model.py`
- Create: `tests/engagement/test_training.py`

**Interfaces:**
- Consumes: manifest-backed `[20, 98]` tensors and integer labels.
- Produces: `EngagementTransformer`, train-only normalization statistics, best checkpoint, and JSON metrics.

- [ ] **Step 1: Write failing model and normalization tests**

```python
def test_transformer_returns_four_logits_and_owns_normalization() -> None:
    model = EngagementTransformer(feature_mean=torch.zeros(98), feature_std=torch.ones(98))
    logits = model(torch.zeros(2, 20, 98))
    assert logits.shape == (2, 4)
    assert "feature_mean" in dict(model.named_buffers())


def test_statistics_use_train_split_only() -> None:
    stats = compute_feature_statistics(train_arrays())
    np.testing.assert_allclose(stats.mean, EXPECTED_TRAIN_MEAN)
    np.testing.assert_allclose(stats.std, EXPECTED_TRAIN_STD)
```

- [ ] **Step 2: Verify RED**

Run: `uv run pytest tests/engagement/test_model.py tests/engagement/test_training.py -v`

Expected: FAIL because model and trainer are missing.

- [ ] **Step 3: Implement the model and deterministic trainer**

```python
class EngagementTransformer(nn.Module):
    def __init__(
        self,
        feature_mean: Tensor,
        feature_std: Tensor,
        *,
        input_dim: int = 98,
        d_model: int = 256,
        nhead: int = 8,
        num_layers: int = 4,
        mlp_dim: int = 128,
        dropout: float = 0.3,
        num_classes: int = 4,
    ) -> None:
        super().__init__()
        self.register_buffer("feature_mean", feature_mean.reshape(1, 1, input_dim))
        self.register_buffer("feature_std", feature_std.clamp_min(1e-6).reshape(1, 1, input_dim))
        self.input_projection = nn.Linear(input_dim, d_model)
        self.position = nn.Parameter(torch.zeros(1, 20, d_model))
        layer = nn.TransformerEncoderLayer(
            d_model, nhead, dim_feedforward=d_model * 4, dropout=dropout,
            activation="gelu", batch_first=True, norm_first=True,
        )
        self.encoder = nn.TransformerEncoder(layer, num_layers=num_layers)
        self.classifier = nn.Sequential(
            nn.Linear(d_model, mlp_dim), nn.ReLU(), nn.Dropout(dropout),
            nn.Linear(mlp_dim, num_classes),
        )

    def forward(self, tokens: Tensor) -> Tensor:
        normalized = (tokens - self.feature_mean) / self.feature_std
        encoded = self.encoder(self.input_projection(normalized) + self.position)
        return self.classifier(encoded.amax(dim=1))

def train_model(config: TrainingConfig) -> TrainingResult:
    seed_everything(config.seed)
    datasets = load_manifest_datasets(config.features_root)
    statistics = compute_feature_statistics(datasets.train.token_arrays())
    model = EngagementTransformer(
        torch.from_numpy(statistics.mean), torch.from_numpy(statistics.std)
    ).to(config.device)
    optimizer = torch.optim.Adam(model.parameters(), lr=config.learning_rate)
    stopper = ValidationStopper(config.patience, mode="max")
    for epoch in range(config.max_epochs):
        train_one_epoch(model, datasets.train.loader(config), optimizer, config)
        metrics = evaluate_model(model, datasets.valid.loader(config))
        stopper.update(metrics.macro_f1, model, epoch, config.output_dir / "best.pt")
        if stopper.should_stop:
            break
    best_model = load_best_model(config.output_dir / "best.pt", statistics)
    test_metrics = evaluate_model(best_model, datasets.test.loader(config))
    return write_training_result(config, statistics, stopper, test_metrics)

def evaluate_model(
    model: nn.Module, loader: DataLoader[tuple[Tensor, Tensor]]
) -> EvaluationMetrics:
    labels, predictions = collect_predictions(model, loader)
    return EvaluationMetrics.from_predictions(labels, predictions, class_names=LABELS)
```

Project 98→256, add a learned `[20, 256]` positional embedding, apply four pre-norm Transformer encoder layers, max-pool over tokens, and classify through `Linear(256,128)`, ReLU, dropout, `Linear(128,4)`. Use unweighted cross entropy by default, optional train-only inverse-frequency weights, Adam at `1e-4`, validation macro F1 checkpointing, patience 20, seed 42, and never select against Test.

- [ ] **Step 4: Verify GREEN**

Run: `uv run pytest tests/engagement/test_model.py tests/engagement/test_training.py -v`

Expected: PASS for forward shape, deterministic tiny optimization, metadata, and metric serialization.

Run: `uv run pytest && uv run ruff check . && uv run mypy src`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add src/zani_ai/engagement/model.py src/zani_ai/engagement/training.py tests/engagement
git commit -m "✨ feat: EngageNet Transformer 학습 및 평가 구현 (S15P11A105-151)"
```

### Task 5: ONNX export and deployment metadata

**Files:**
- Create: `src/zani_ai/engagement/export.py`
- Create: `tests/engagement/test_export.py`

**Interfaces:**
- Consumes: a real trained checkpoint and training metadata.
- Produces: `engagement.onnx` and `engagement.metadata.json`; `export_onnx(model, metadata, output_dir, opset_version) -> ExportResult`.

- [ ] **Step 1: Write a failing numerical parity test**

```python
def test_exported_onnx_matches_pytorch(tmp_path: Path) -> None:
    model = deterministic_model()
    result = export_onnx(model, deployment_metadata(), tmp_path)
    tokens = np.arange(20 * 98, dtype=np.float32).reshape(1, 20, 98) / 1000
    torch_logits = model(torch.from_numpy(tokens)).detach().numpy()
    onnx_logits = ort.InferenceSession(str(result.model_path)).run(None, {"tokens": tokens})[0]
    np.testing.assert_allclose(onnx_logits, torch_logits, rtol=1e-4, atol=1e-5)
```

- [ ] **Step 2: Verify RED**

Run: `uv run pytest tests/engagement/test_export.py -v`

Expected: FAIL because `export_onnx` is missing.

- [ ] **Step 3: Implement validated export**

```python
@dataclass(frozen=True, slots=True)
class DeploymentMetadata:
    schema: Literal["mediapipe_98_v1"]
    input_name: str
    input_shape: tuple[int | str, int, int]
    labels: tuple[str, ...]
    window_seconds: float
    segment_count: int
    sample_fps: float

def export_onnx(
    model: EngagementTransformer,
    metadata: DeploymentMetadata,
    output_dir: Path,
    *,
    opset_version: int = 18,
) -> ExportResult:
    output_dir.mkdir(parents=True, exist_ok=True)
    temporary_model = output_dir / "engagement.onnx.tmp"
    example = torch.arange(20 * 98, dtype=torch.float32).reshape(1, 20, 98) / 1000
    torch.onnx.export(
        model, example, temporary_model, input_names=[metadata.input_name],
        output_names=["logits"], dynamic_axes={metadata.input_name: {0: "batch"}},
        opset_version=opset_version, dynamo=False,
    )
    exported = onnx.load(temporary_model)
    onnx.checker.check_model(exported)
    expected = model(example).detach().cpu().numpy()
    actual = ort.InferenceSession(str(temporary_model)).run(
        ["logits"], {metadata.input_name: example.numpy()}
    )[0]
    np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=1e-5)
    model_path = output_dir / "engagement.onnx"
    temporary_model.replace(model_path)
    metadata_path = _atomic_write_json(output_dir / "engagement.metadata.json", asdict(metadata))
    return ExportResult(model_path, metadata_path)
```

Export with dynamic batch only, run `onnx.checker`, execute a deterministic numerical parity input, and write both files atomically only after validation. Do not create an export when a trained checkpoint is absent.

- [ ] **Step 4: Verify GREEN**

Run: `uv run pytest tests/engagement/test_export.py -v`

Expected: PASS and no committed model artifact.

- [ ] **Step 5: Commit**

```powershell
git add src/zani_ai/engagement/export.py tests/engagement/test_export.py
git commit -m "✨ feat: 검증된 ONNX 모델 내보내기 추가 (S15P11A105-151)"
```

### Task 6: Python CLI and reproduction documentation

**Files:**
- Create: `src/zani_ai/engagement/cli.py`
- Modify: `src/zani_ai/__main__.py`
- Create: `tests/engagement/test_cli.py`
- Modify: `README.md`
- Modify: `datasets/README.md`

**Interfaces:**
- Produces: `python -m zani_ai engagement validate|extract|train|export` commands.

- [ ] **Step 1: Write failing CLI behavior tests**

```python
def test_validate_reports_required_files_when_dataset_is_absent(tmp_path: Path) -> None:
    result = run_cli("engagement", "validate", "--data-root", str(tmp_path))
    assert result.returncode == 2
    assert "final_labels.csv" in result.stderr
    assert "train.txt" in result.stderr


def test_train_never_generates_sample_data(tmp_path: Path) -> None:
    result = run_cli("engagement", "train", "--features", str(tmp_path / "missing"))
    assert result.returncode == 2
    assert list(tmp_path.iterdir()) == []
```

- [ ] **Step 2: Verify RED**

Run: `uv run pytest tests/engagement/test_cli.py -v`

Expected: FAIL because the engagement command is not registered.

- [ ] **Step 3: Implement argparse subcommands and exact README commands**

```python
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m zani_ai engagement")
    commands = parser.add_subparsers(dest="command", required=True)
    _add_validate_parser(commands)
    _add_extract_parser(commands)
    _add_train_parser(commands)
    _add_export_parser(commands)
    return parser

def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return COMMANDS[args.command](args)
    except UserFacingError as error:
        print(str(error), file=sys.stderr)
        return 2
```

Document these commands with real paths only:

```powershell
uv run python -m zani_ai engagement validate --data-root datasets/raw/engagenet
uv run python -m zani_ai engagement extract --data-root datasets/raw/engagenet --face-landmarker-model models/face_landmarker.task --output datasets/processed/engagenet
uv run python -m zani_ai engagement train --features datasets/processed/engagenet --output artifacts/engagement/run-001
uv run python -m zani_ai engagement export --checkpoint artifacts/engagement/run-001/best.pt --output web/engagement-demo/public/models
```

Explain how to request EngageNet and download the official MediaPipe Face Landmarker asset. Clearly state that the repository contains neither dataset nor trained model.

- [ ] **Step 4: Verify GREEN**

Run: `uv run pytest tests/engagement/test_cli.py -v`

Expected: PASS and absent data produces a concise exit-code-2 error.

Run: `uv run python -m zani_ai engagement validate --data-root datasets/raw/engagenet`

Expected: exit 2 listing missing required files; no files created.

- [ ] **Step 5: Commit**

```powershell
git add README.md datasets/README.md src/zani_ai/__main__.py src/zani_ai/engagement/cli.py tests/engagement/test_cli.py
git commit -m "✨ feat: EngageNet 재현 CLI와 실행 문서 추가 (S15P11A105-151)"
```

### Task 7: TypeScript feature parity and rolling window core

**Files:**
- Create: `web/engagement-demo/package.json`
- Create: `web/engagement-demo/package-lock.json`
- Create: `web/engagement-demo/tsconfig.json`
- Create: `web/engagement-demo/vite.config.ts`
- Create: `web/engagement-demo/src/contracts.ts`
- Create: `web/engagement-demo/src/features.ts`
- Create: `web/engagement-demo/src/rolling-window.ts`
- Create: `web/engagement-demo/src/features.test.ts`
- Create: `web/engagement-demo/src/rolling-window.test.ts`

**Interfaces:**
- Consumes: MediaPipe web landmark/blendshape/matrix values.
- Produces: `extractFrameFeatures(...) -> Float32Array(49)` and `RollingFeatureWindow.tokens() -> Float32Array(20*98)`.

- [ ] **Step 1: Scaffold test configuration only and write failing parity tests**

```typescript
it("matches the mediapipe_98_v1 feature order", () => {
  const values = extractFrameFeatures(knownLandmarks(), knownTransform(), knownBlendshapes());
  expect(values).toHaveLength(49);
  expect(Array.from(values)).toEqual(expectedFrameFeatures());
});

it("requires three valid frames in every half-second segment", () => {
  const window = new RollingFeatureWindow({ windowMs: 10_000, segments: 20, minFrames: 3 });
  addOnlyTwoFramesToFirstSegment(window);
  expect(window.tokens()).toBeNull();
});
```

- [ ] **Step 2: Verify RED**

Run: `npm --prefix web/engagement-demo test -- --run`

Expected: FAIL because core modules do not exist.

- [ ] **Step 3: Port the exact Python math without browser APIs**

```typescript
export const SCHEMA_NAME = "mediapipe_98_v1" as const;
export const RAW_FEATURE_COUNT = 49;
export const TOKEN_FEATURE_COUNT = 98;

export function extractFrameFeatures(input: FrameLandmarkerValues): Float32Array {
  const right = normalizedEyePosition(irisCenter(input.landmarks, 468), input.landmarks, RIGHT_EYE);
  const left = normalizedEyePosition(irisCenter(input.landmarks, 473), input.landmarks, LEFT_EYE);
  const gaze = [
    ...right, ...left, (right[0] + left[0]) / 2, (right[1] + left[1]) / 2,
    right[0] - left[0], right[1] - left[1],
  ];
  const [yaw, pitch, roll] = matrixToEulerXyz(input.transform);
  const distance = Math.max(pointDistance(input.landmarks[33], input.landmarks[263]), 1e-6);
  const head = [yaw, pitch, roll, input.landmarks[1].x, input.landmarks[1].y, 1 / distance];
  const face = BLENDSHAPE_NAMES.map((name) => input.blendshapes.get(name) ?? 0);
  const result = Float32Array.from([...gaze, ...head, ...face]);
  if (result.length !== RAW_FEATURE_COUNT || result.some((value) => !Number.isFinite(value))) {
    throw new Error("49개의 유효한 MediaPipe 특징이 필요합니다.");
  }
  return result;
}

export class RollingFeatureWindow {
  private readonly frames: TimedFrame[] = [];

  add(timestampMs: number, values: Float32Array | null): void {
    this.frames.push({ timestampMs, values });
    const cutoff = timestampMs - this.options.windowMs;
    while (this.frames[0]?.timestampMs < cutoff) this.frames.shift();
  }

  progress(nowMs: number): number {
    const first = this.frames[0]?.timestampMs ?? nowMs;
    return Math.min(1, Math.max(0, (nowMs - first) / this.options.windowMs));
  }

  tokens(nowMs: number): Float32Array | null {
    if (this.progress(nowMs) < 1) return null;
    return aggregateWindow(this.frames, nowMs - this.options.windowMs, this.options);
  }

  clear(): void { this.frames.length = 0; }
}
```

Keep this layer independent from DOM, camera, MediaPipe, and ONNX so Vitest can run in Node. Match Python float tolerances rather than exact bit equality where trigonometry is involved.

- [ ] **Step 4: Verify GREEN**

Run: `npm --prefix web/engagement-demo test -- --run`

Expected: PASS.

Run: `npm --prefix web/engagement-demo run typecheck`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add web/engagement-demo
git commit -m "✨ feat: 웹 MediaPipe 특징 및 시간 창 구현 (S15P11A105-151)"
```

### Task 8: Browser model adapter and real-time page

**Files:**
- Create: `web/engagement-demo/index.html`
- Create: `web/engagement-demo/src/model.ts`
- Create: `web/engagement-demo/src/model.test.ts`
- Create: `web/engagement-demo/src/mediapipe.ts`
- Create: `web/engagement-demo/src/app.ts`
- Create: `web/engagement-demo/src/styles.css`
- Create: `web/engagement-demo/public/models/README.md`

**Interfaces:**
- Consumes: webcam frames, pinned MediaPipe Tasks assets, `engagement.onnx`, and `engagement.metadata.json`.
- Produces: local four-class probability updates after a valid rolling window is available.

- [ ] **Step 1: Write failing metadata and softmax tests**

```typescript
it("rejects an unsupported feature schema before creating a session", async () => {
  await expect(validateMetadata({ ...validMetadata(), schema: "mediapipe_132_v1" }))
    .rejects.toThrow("지원하지 않는 특징 스키마");
});

it("converts four logits into probabilities summing to one", () => {
  const probabilities = softmax(new Float32Array([1, 2, 3, 4]));
  expect(probabilities.reduce((sum, value) => sum + value, 0)).toBeCloseTo(1);
});
```

- [ ] **Step 2: Verify RED**

Run: `npm --prefix web/engagement-demo test -- --run`

Expected: FAIL because `model.ts` is absent.

- [ ] **Step 3: Implement metadata validation and ONNX inference adapter**

```typescript
export async function createEngagementModel(
  modelUrl = "/models/engagement.onnx",
  metadataUrl = "/models/engagement.metadata.json",
): Promise<EngagementModel> {
  const response = await fetch(metadataUrl);
  if (!response.ok) throw new Error("학습된 모델 메타데이터가 없습니다.");
  const metadata = validateMetadata(await response.json());
  const executionProviders = "gpu" in navigator ? ["webgpu", "wasm"] : ["wasm"];
  const session = await ort.InferenceSession.create(modelUrl, { executionProviders });
  return {
    async predict(tokens: Float32Array): Promise<Prediction> {
      const input = new ort.Tensor("float32", tokens, [1, 20, 98]);
      const output = await session.run({ [metadata.input_name]: input });
      const probabilities = softmax(output.logits.data as Float32Array);
      return predictionFromProbabilities(probabilities, metadata.labels);
    },
    async dispose(): Promise<void> { await session.release(); },
  };
}

export interface EngagementModel {
  predict(tokens: Float32Array): Promise<Prediction>;
  dispose(): Promise<void>;
}
```

Validate schema, `[batch,20,98]`, labels, and input name before inference. Select WebGPU when available and fall back to WASM. Render model-load failures as setup instructions, never as fabricated predictions.

- [ ] **Step 4: Implement MediaPipe/camera lifecycle and the minimal UI**

Use `FaceLandmarker.createFromOptions` with `runningMode: "VIDEO"`, `outputFaceBlendshapes: true`, and `outputFacialTransformationMatrixes: true`. Process at most 10 FPS, draw the landmark overlay, update collection progress, run prediction at a one-second interval after the first valid 10-second window, and dispose camera tracks, MediaPipe, and ONNX resources on Stop or page unload.

The page must include video/canvas, Start/Stop, progress, current class, four probability bars, status text, and a bounded prediction history. Use clear Korean copy and responsive CSS; no backend URL or upload code may exist.

- [ ] **Step 5: Verify unit tests and production build**

Run: `npm --prefix web/engagement-demo test -- --run`

Expected: PASS.

Run: `npm --prefix web/engagement-demo run typecheck && npm --prefix web/engagement-demo run build`

Expected: PASS and `dist/` contains the static app. With no trained model, the page must show the missing-model setup message.

- [ ] **Step 6: Commit**

```powershell
git add web/engagement-demo
git commit -m "✨ feat: 브라우저 실시간 참여도 추론 페이지 구현 (S15P11A105-151)"
```

### Task 9: End-to-end verification and handoff

**Files:**
- Modify: `README.md`
- Modify: `.gitignore`

**Interfaces:**
- Verifies all earlier deliverables; produces no dataset or model artifacts.

- [ ] **Step 1: Run the complete Python quality suite**

Run: `uv run pytest --cov=zani_ai --cov-report=term-missing`

Expected: all tests PASS.

Run: `uv run ruff check . && uv run mypy src`

Expected: both PASS with no errors.

- [ ] **Step 2: Run the complete web quality suite**

Run: `npm --prefix web/engagement-demo test -- --run`

Expected: all tests PASS.

Run: `npm --prefix web/engagement-demo run typecheck && npm --prefix web/engagement-demo run build`

Expected: PASS.

- [ ] **Step 3: Verify the no-data path and repository hygiene**

Run: `uv run python -m zani_ai engagement validate --data-root datasets/raw/engagenet`

Expected: exit 2 with required-file guidance and no sample data created.

Run: `git status --short --ignored`

Expected: source/docs/tests are trackable; caches, `datasets/processed`, `artifacts`, `.task`, `.onnx`, and web `dist` are ignored.

- [ ] **Step 4: Perform a browser smoke test**

Run: `npm --prefix web/engagement-demo run dev -- --host 127.0.0.1`

Expected: the page loads, explains the absent ONNX model, requests no camera access until Start, and sends no video/feature network requests. A real prediction cannot be verified until a trained ONNX model exists.

- [ ] **Step 5: Commit final documentation adjustments**

```powershell
git add README.md .gitignore
git commit -m "📝 docs: EngageNet 재현 검증 절차 정리 (S15P11A105-151)"
```
