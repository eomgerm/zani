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

Windows에서 NVIDIA GPU로 E0를 실행할 때는 공식 PyTorch CUDA 13.0 인덱스가
`pyproject.toml`에 고정되어 있으므로 다음 명령으로 필요한 환경을 한 번에 구성합니다.

```powershell
uv sync --extra vision --extra train --extra eda --no-dev
uv run python -c "import torch; print(torch.__version__, torch.cuda.is_available())"
```

Windows에서 CUDA build는 필요한 CUDA runtime을 wheel에 포함하므로 별도 CUDA Toolkit 설치는
필수가 아니지만, 호환되는 NVIDIA 드라이버가 설치되어 있어야 합니다.

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
  --output datasets/processed/engagenet

uv run python -m zani_ai engagement train `
  --features datasets/processed/engagenet `
  --output artifacts/engagement/run-001

uv run python -m zani_ai engagement export `
  --checkpoint artifacts/engagement/run-001/best.pt `
  --output web/engagement-demo/public/models
```

### E0 5-seed 재현

E0는 Test 분할을 평가하지 않고 Validation Macro-F1로만 조기 종료와 체크포인트를
선택합니다. 기본 seed는 `42,43,44,45,46`이며 다음 명령으로 정확한 E0 설정을 실행합니다.

```powershell
uv run python -m zani_ai engagement reproduce-e0 --features <root> --output <dir> --device cuda
```

완료된 seed는 동일한 특징 manifest와 설정 및 실행 환경을 확인한 뒤 재사용됩니다.
각 seed 완료 시 `summary.json`을 원자적으로 갱신하므로 중단된 실행도 다시 시작할 수 있습니다.

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
