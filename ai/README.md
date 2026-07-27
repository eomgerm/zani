# ZANI AI

MediaPipe 기반 학습 반응 특징 추출과 PyTorch 모델 학습을 위한 ZANI의 Python 모듈입니다.
2023 EngageNet의 Gaze + Head Pose + AU Transformer를 98차원 MediaPipe 특징으로
적응해 재현합니다. 공식 데이터 검증, 특징 추출, PyTorch 학습·평가, ONNX 변환과
브라우저 실시간 추론 페이지를 제공합니다.

## 요구 사항

- uv
- Python 3.12 (필요한 경우 uv가 자동으로 설치합니다.)

## 환경 구성

```powershell
uv sync --group dev
```

선택 의존성은 필요한 작업에 맞춰 설치합니다.

```powershell
uv sync --extra vision --group dev
uv sync --extra train --group dev
uv sync --extra vision --extra train --group dev
```

NVIDIA GPU로 학습할 때 쓸 PyTorch CUDA 빌드는 `pyproject.toml`에 플랫폼별로 고정되어
있습니다. Windows는 CUDA 13.0(`torch 2.13.0+cu130`), Linux는 CUDA 12.8
(`torch 2.11.0+cu128`)입니다. Linux를 12.8로 잡은 것은 학습 서버의 드라이버
570.211.01이 CUDA 12.8까지만 지원하기 때문입니다. CUDA 13 런타임은 드라이버 580 이상을
요구합니다.

```powershell
uv sync --extra vision --extra train --extra eda --no-dev
uv run python -c "import torch; print(torch.__version__, torch.cuda.is_available())"
```

```bash
uv sync --extra vision --extra train --extra eda --no-dev
uv run python -c "import torch; print(torch.__version__, torch.cuda.is_available())"
```

CUDA build는 필요한 CUDA runtime을 wheel에 포함하므로 별도 CUDA Toolkit 설치는
필수가 아니지만, 호환되는 NVIDIA 드라이버가 설치되어 있어야 합니다.

아래 명령들은 PowerShell 기준입니다. bash에서는 줄 이어쓰기를 백틱(`` ` ``) 대신
백슬래시(`\`)로 바꾸면 그대로 동작합니다. 원격 L40S 서버(JupyterHub)에서의 실행 절차는
[docs/remote-l40s.md](docs/remote-l40s.md)를 참고하세요.

## EngageNet 데이터 준비

데이터셋은 저자에게 요청해 별도로 받아야 하며 저장소에는 포함되지 않습니다.
사용자가 보유한 원본의 `Train/`, `Validation/`, `Test/` 폴더와 라벨 파일은 다음 명령으로
기존 계약으로 준비합니다. 이 작업은 MP4를 복사하지 않고 Windows NTFS hard link만 만듭니다.

```powershell
uv run --extra eda python scripts/prepare_engagenet_contract.py `
  --source-root <EngageNet-원본-루트> `
  --output-root datasets/raw/engagenet
```

SNP(Subject Not Present)는 학습 대상이 아니므로 제외됩니다. 기대되는 4-class clip 수는
Train 7,879개, Validation 1,071개, Test 2,256개입니다. 준비 결과에는 원본 라벨 파일
SHA-256, 분할·라벨별 원본/포함 수, 제외 SNP 목록, subject 수, link 전략을 담은
`preparation_manifest.json`이 생성됩니다.

다음 공식 베이스라인 구조로 배치합니다.

```text
datasets/raw/engagenet/
├─ final_labels.csv
├─ train.txt
├─ valid.txt
├─ test.txt
└─ videos/
   └─ <clip-id>.mp4
```

MediaPipe의 공식 Face Landmarker 모델 번들도 별도로 내려받아
`models/face_landmarker.task`에 둡니다. `.task`, 데이터셋, 추출 특징, 체크포인트와
ONNX 모델은 Git에 포함되지 않습니다.

```powershell
New-Item -ItemType Directory -Force models | Out-Null
Invoke-WebRequest `
  'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task' `
  -OutFile 'models/face_landmarker.task'
```

## 재현 파이프라인

```powershell
uv run python -m zani_ai engagement validate `
  --data-root datasets/raw/engagenet

uv run python -m zani_ai engagement extract `
  --data-root datasets/raw/engagenet `
  --face-landmarker-model models/face_landmarker.task `
  --output datasets/processed/engagenet `
  --workers 2 `
  --progress-every 25 `
  --max-excluded-fraction 0.05

uv run python -m zani_ai engagement train `
  --features datasets/processed/engagenet `
  --output artifacts/engagement/run-001

uv run python -m zani_ai engagement export `
  --checkpoint artifacts/engagement/run-001/best.pt `
  --output web/engagement-demo/public/models
```

위 명령은 각 worker process에 독립적인 Face Landmarker를 만들며, 32GB RAM과
i7-13700H 노트북을 위한 보수적인 기본값도 worker 2개입니다. 중단 후 같은 명령을 다시
실행하면 원본 영상과 추출 프로토콜 fingerprint가 일치하는 clip별 원자적 `.npz` cache를
재사용합니다. `manifest.json`은 진행 중에도 원자적으로 갱신되고 `processed/total`, cache,
포함·제외 수, 처리 속도와 ETA를 출력합니다. 최종 manifest에는 MediaPipe/OpenCV 버전,
Face Landmarker 모델 SHA-256과 크기, sampling/segment/feature schema, worker 수와 실제 제외
임계값이 기록됩니다. 완료되지 않았거나 제외 임계값을 넘은 manifest로는 학습을 시작하지
않습니다.

### E0 5-seed 재현

E0는 Test 분할을 평가하지 않고 Validation Macro-F1로만 조기 종료와 체크포인트를
선택합니다. 기본 seed는 `42,43,44,45,46`이며 다음 명령으로 정확한 E0 설정을 실행합니다.

```powershell
uv run python -m zani_ai engagement reproduce-e0 --features <root> --output <dir> --device cuda
```

`--device`는 `cpu`, `cuda`, `cuda:N`을 받습니다. 여러 GPU가 있는 공용 서버에서는
`CUDA_VISIBLE_DEVICES`로 사용할 카드를 좁히는 쪽이 안전합니다. 카드 번호는 재현성
identity(`configuration_sha256`)에 들어가지 않으므로 `cuda`와 `cuda:2`는 같은 실험입니다.

완료된 seed는 동일한 특징 manifest와 설정 및 실행 환경을 확인한 뒤 재사용됩니다.
각 seed 완료 시 `summary.json`을 원자적으로 갱신하므로 중단된 실행도 다시 시작할 수 있습니다.
다른 하드웨어에서 이어받아야 한다면 `--allow-environment-drift`를 지정합니다. 이때도
PyTorch 버전, CUDA 런타임, `CUBLAS_WORKSPACE_CONFIG`, device 종류는 일치해야 하며,
seed마다 실제 실행 환경이 `summary.json`에 기록됩니다.

`reproduce-e0c`와 `reproduce-e0d`는 E0와 손실 가중치만 다른 프로토콜입니다. E0의 오분류는
89%가 인접 등급 한 칸 차이이고 경계가 다수 클래스(Highly-Engaged, Validation의 56%)
쪽으로 밀려 있어, 인코더가 아니라 손실이 병목으로 보이기 때문입니다. E0-C는
`balanced`(`N / (4 × n_i)`, 이 데이터에서 최대/최소 약 7.7배), E0-D는 `sqrt_balanced`
(`w ∝ 1/√n`, 약 2.8배)를 씁니다. 두 스킴 모두 `Σ(n_i × w_i) = N`으로 정규화되므로 손실
크기가 유지되어 학습률을 그대로 쓸 수 있습니다. `class_weighting`은 재현성
identity에 포함되므로 각각 별도 프로토콜이며 기존 E0 결과는 그대로 남습니다.

`reproduce-e1a`는 E1과 학습 조건만 다릅니다. E1이 재현하려는 논문
(arXiv:2403.17175)은 batch 16, lr 1e-3으로 300 epoch을 완주하며 100·200에서
학습률을 0.1배로 감쇠합니다. E1은 처리량을 위해 batch 32 / lr 2e-3을 쓰고
`patience=20`으로 조기 종료했는데, 실측 `best_epoch`이 44·48·13·8·25라
어느 seed도 첫 감쇠에 도달하지 못했습니다. E1-A는 논문값으로 되돌리고
`patience`를 `max_epochs`와 같게 두어 조기 종료를 끕니다. 체크포인트 선택은
그대로 Validation Macro-F1 최고점입니다.

`reproduce-e1b`는 E1-A에 논문의 시간 해상도를 더합니다. 논문은 10초 클립의 30fps
300프레임을 전부 쓰는데 우리는 10fps로 3프레임 중 하나만 씁니다. 논문 Table 5는
2프레임마다로만 성겨져도 0.7124 → 0.6813으로 3.1%p 떨어진다고 보고하므로, 이것이
남은 차이 중 가장 큽니다.

`SAMPLE_FPS`가 **추출 단계에서** 프레임을 버리므로 `raw_frames_v1`로는 300스텝을 만들
수 없습니다. 원본 영상에서 30fps로 다시 추출해야 합니다. `sample_fps`는 해시되는
extraction fingerprint에 포함되어 있어 rate가 다른 캐시가 조용히 섞이지 않고,
10fps는 `raw_frames_v1`/`landmark_78_v1` 이름을 그대로 유지하므로 기존 캐시와
체크포인트가 살아 있습니다.

```bash
uv run python -m zani_ai engagement extract-raw \
  --data-root datasets/raw/engagenet \
  --face-landmarker-model models/face_landmarker.task \
  --output datasets/processed/engagenet \
  --sample-fps 30 --workers 64

uv run python -m zani_ai engagement build-features \
  --data-root datasets/raw/engagenet \
  --raw-root datasets/processed/engagenet/raw_frames_30fps_v1 \
  --output datasets/processed/engagenet/e1b \
  --schema landmark_78_300_v1 --sample-fps 30
```

`landmark_78_v1_graph.npz`는 재사용합니다. 노드 평균 위치는 프레임 수와 무관하게
사실상 같고, 변수를 하나로 묶어두는 편이 비교에 유리합니다.

E1은 ST-GCN 노드 토폴로지를 정의하는 `landmark_78_v1_graph.npz`가 필요합니다.
`--graph`, 환경변수 `ZANI_LANDMARK_GRAPH`, `--features` 디렉터리, 그 부모 순으로 찾고,
찾은 파일의 SHA-256을 `summary.json`의 `inputs.landmark_graph`에 기록합니다.

```text
artifacts/engagement/e0/
├─ summary.json
├─ seed-42/
│  ├─ best.pt
│  ├─ metrics.json              # Validation 결과만 포함
│  └─ onnx/
│     ├─ engagement.onnx
│     └─ engagement.metadata.json
├─ seed-43/
├─ seed-44/
├─ seed-45/
└─ seed-46/
```

`summary.json`에는 5개 seed의 Validation Accuracy/Macro-F1 평균과 표본 표준편차가
기록됩니다. E0 단계의 Test 평가는 프로토콜에 따라 보류되며 summary와 seed별
`metrics.json`에는 Test metric, label 또는 prediction을 기록하지 않습니다.

데이터가 없으면 `validate`, `extract`, `train`은 필요한 파일을 안내하고 종료합니다.
샘플 영상, 합성 학습 데이터나 가짜 모델을 자동 생성하지 않습니다. 실제 정확도와 F1은
공식 데이터로 학습한 뒤 `metrics.json`에서 확인합니다.

## 브라우저 실시간 추론

ONNX export를 완료한 뒤 웹 앱을 실행합니다.

```powershell
cd web/engagement-demo
npm ci
npm run dev
```

표시된 localhost 주소를 열고 `카메라 시작`을 누릅니다. MediaPipe Face Landmarker는
10FPS로 특징을 계산하고, ONNX Runtime Web은 최근 10초 창을 1초마다 갱신합니다.
카메라 권한은 버튼을 누른 뒤에만 요청하며 영상과 특징은 서버로 전송하지 않습니다.
운영 배포에서는 카메라 API를 사용할 수 있도록 HTTPS가 필요합니다.

## 실행

```powershell
uv run python -m zani_ai
```

## 검증

```powershell
uv run pytest
uv run ruff check .
uv run mypy src
```

## 의존성 그룹

- `vision`: MediaPipe(OpenCV 포함), NumPy
- `train`: PyTorch, scikit-learn, ONNX, ONNX Runtime
- `dev`: pytest, coverage, Ruff, mypy

## 저장소 정책

소스 코드, 테스트, `pyproject.toml`, `uv.lock`, `.python-version`, 문서는 Git에 포함합니다.
가상환경, 데이터셋, 추출된 생체 특징, 모델 가중치, 체크포인트, 실험 로그는 포함하지 않습니다.
