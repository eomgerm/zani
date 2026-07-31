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
[../.agents/ai-remote-l40s-guide.md](../.agents/ai-remote-l40s-guide.md)를 참고하세요.

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

위 명령은 **clip 하나마다 Face Landmarker를 새로 만듭니다.** Face Landmarker는 VIDEO
모드로 동작해 앞 프레임의 추적 상태를 다음 호출에 넘기므로, landmarker를 여러 clip에
재사용하면 한 clip의 마지막 프레임이 다음 clip의 탐지에 섞여 들어갑니다. 그러면 clip의
특징값이 worker 수와 처리 순서에 따라 달라지고, 같은 영상으로 두 번 추출해도 서로 다른
데이터셋이 나옵니다. clip마다 새로 만들면 특징이 그 clip만의 함수가 됩니다. 이 범위는
`landmarker_scope`로 fingerprint에 들어가므로, **이 규칙 이전에 만든 cache는 재사용되지
않고 다시 추출됩니다.**

32GB RAM과 i7-13700H 노트북을 위한 보수적인 기본값은 worker 2개입니다. 중단 후 같은
명령을 다시 실행하면 원본 영상과 추출 프로토콜 fingerprint가 일치하는 clip별 원자적
`.npz` cache를 재사용합니다. `manifest.json`은 진행 중에도 원자적으로 갱신되고 `processed/total`, cache,
포함·제외 수, 처리 속도와 ETA를 출력합니다. 최종 manifest에는 MediaPipe/OpenCV 버전,
Face Landmarker 모델 SHA-256과 크기, sampling/segment/feature schema, worker 수와 실제 제외
임계값이 기록됩니다. 완료되지 않았거나 제외 임계값을 넘은 manifest로는 학습을 시작하지
않습니다.

### 브라우저 프레임 게이트 정합 감사

브라우저는 10초 동안 기대한 100프레임 중 유효 프레임이 70개 미만이면 추론하지
않습니다. 기존 세그먼트 조건(20개 세그먼트마다 3프레임 이상)은 통과하지만 이 총량
조건에서 제외되는 60~69프레임 클립은 raw cache에서 다음 명령으로 집계합니다.

```powershell
uv run python -m zani_ai engagement audit-frame-gate `
  --data-root datasets/raw/engagenet `
  --raw-root datasets/processed/engagenet/raw_frames_v1 `
  --output artifacts/engagement/frame-gate-audit.json
```

출력 JSON은 전체 불일치 수, 분할별·등급별·분할×등급별 수와 해당 clip ID를 기록합니다.
수정된 98D 특징과 `manifest.json`은 MediaPipe를 다시 실행하지 않고 raw cache에서
재생성할 수 있습니다.

```powershell
uv run python -m zani_ai engagement build-features `
  --data-root datasets/raw/engagenet `
  --raw-root datasets/processed/engagenet/raw_frames_v1 `
  --output datasets/processed/engagenet `
  --schema mediapipe_98_v1 `
  --sample-fps 10
```

`artifacts/engagement/`의 체크포인트와 지표는 건드리지 않습니다. 다만 **특징 캐시는
보존되지 않습니다.** `extract`와 `build-features`는 특징을
`<output_root>/mediapipe_98_v1/<split>/`에, `manifest.json`을 `<output_root>/manifest.json`에
쓰는 경로가 같아서, 같은 `--output`을 주면 뒤에 실행한 쪽이 앞의 산출물을 덮어씁니다.
`manifest.json`에 어느 쪽이 썼는지 `pipeline` 필드로 기록하고 출처가 다르면 쓰기를
거부하므로 사고로 덮이지는 않지만, **다른 파이프라인의 결과를 남겨두려면 `--output`을
다른 경로로 지정해야 합니다.**

새 `manifest.json`의 SHA-256이 달라지므로 재현성 identity 검증이 이전 결과의 재사용을
차단합니다.

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

`reproduce-e0e`와 `reproduce-e0f`는 E0-D가 E0 대비 Validation Macro F1 +0.49%p에 그친
뒤 남은 두 축을 각각 하나씩만 바꿉니다.

E0-E는 E0-D의 `sqrt_balanced` 가중치를 그대로 두고 손실 모양만 Focal
(`FL = -α_t (1 - p_t)^γ log p_t`, `γ = 2.0`)로 바꿉니다. 빈도 가중치는 다수 클래스의 쉬운
표본과 어려운 표본을 구분하지 못하지만 `(1 - p_t)^γ`는 구분합니다. 감쇠(reduction)는
`CrossEntropyLoss(weight=...)`와 같은 가중 평균이라 손실 크기가 유지되고 학습률을 그대로
씁니다. 따라서 E0-D와의 차이는 Focal 항 하나로 귀속됩니다.

E0-F는 보정을 손실에서 배치로 옮깁니다. `WeightedRandomSampler`가 표본 가중
`∝ 1/n_i`로 `len(train)`개를 복원추출해 epoch마다 클래스 균등에 가까운 배치를 만들고,
손실은 **가중 없는 CE로 되돌립니다**. 균형 샘플러 위에 가중 손실을 얹으면 같은 불균형을
두 번 보정하기 때문입니다. 샘플러는 학습 분할에만 적용되며, 평가 분할을 다시 뽑으면
Macro F1을 재는 분포 자체가 바뀌므로 적용하지 않습니다. 복원추출은 확률 과정이라
`config.seed`로 시드된 `torch.Generator`를 주입해 재현성을 유지합니다.

`loss`·`focal_gamma`·`sampler`도 재현성 identity에 포함됩니다. 다만 기본값을 벗어난
프로토콜에서만 `configuration`에 기록되므로, 이들이 없던 기존 8개 프로토콜의
`configuration_sha256`은 바뀌지 않고 완료된 seed도 그대로 재사용됩니다.

`reproduce-e0g`는 손실·샘플링이 아니라 학습 일정을 정비한 baseline 재설정입니다.
E0 계열은 patience 20에 `best_epoch`이 0~4로, 어떤 보정도 결정 경계를 재형성할
시간을 얻지 못했습니다. E0-G는 lr 1e-5(10배 인하), 조기 종료 해제
(patience = max_epochs = 200), 100 epoch에서 0.1배 감쇠를 하나의 변수군으로
적용합니다. 선택 지표는 그대로 Validation Macro-F1이며, 이때부터 모든 평가에
within-1 정확도와 quadratic weighted kappa가 함께 기록됩니다(선택에는 미사용).

`lr_step`은 원래 ST-GCN 경로에만 있던 키라, Transformer 경로에서는 스케줄을 실제로
쓰는 스펙에서만 `configuration`에 기록됩니다. E0-G 이전 Transformer 프로토콜의
`configuration_sha256`은 그대로입니다.

순서 지표는 `metrics.json`의 `validation`·`test`, `summary.json`의 seed 레코드와
`aggregate`, `test_results.json`의 `aggregate`까지 흐릅니다. `metrics.json`에는
epoch별 Validation Macro-F1·QWK 궤적이 `validation_history`로 함께 남아, "QWK로
골랐다면 다른 epoch이 뽑혔을까"를 재학습 없이 사후 분석할 수 있습니다. 지표 도입
전에 완료된 seed 레코드가 재개 경로로 돌아와도 집계는 깨지지 않고, 해당 지표만
빠진 채 집계됩니다.

`reproduce-e0h`는 손실이 맞추려는 **타깃**을 바꿉니다. E0~E0-F 전 구간에서 Test 오분류의
79.2~80.4%가 인접 등급 한 칸 차이로 고정되어 있었고, Test에서 직접 고른 임계값으로
로짓을 조정한 oracle 상한도 +0.4%p에 그쳐 결정 규칙이 아니라 타깃이 병목이라는 쪽을
가리켰습니다. E0-H는 SORD(Diaz & Marathe, CVPR 2019)의 soft 타깃
`target_j ∝ exp(-α (i - j)²)`(`α = 2.0`, i는 정답 등급)를 씁니다. 가운데 등급이면 정답에
약 0.79와 양옆 한 칸에 각 0.11, 양끝 등급이면 0.88과 0.12가 남아 인접 등급 혼동을 타깃
수준에서 겨냥합니다. 사람 평가자도
정확 일치는 46.25%지만 ±1 허용은 88.75%이므로, 이웃에 확률을 남기는 타깃이 one-hot보다
정답지에 가깝습니다.

순서 정보를 쓴 기존 시도 E0-B(CORAL, macro-F1 0.5185)와 실패 지점이 다릅니다. CORAL은
softmax head 자체를 누적 이진 로짓으로 교체하지만, SORD는 **head와 argmax 디코딩·지표를
그대로 두고 타깃 분포만** 바꿉니다.

E0-H는 티켓 210이 사전등록한 E0-G가 아니라 **E0을 기준**으로 놓습니다. 근거는 셋이며
"E0-G가 실패했으니"가 아닙니다. ① 비용 비대칭 — E0-G는 조기 종료를 끄므로 200 epoch ×
5 seed = 1000이지만, E0은 실측 `best_epoch` [1,3,1,2,9]에 patience 20을 더해 116입니다.
싼 쪽을 먼저 시도하는 비용은 실패 시 +12%, 성공 시 88% 절약입니다. ② 비교 가족 — SORD는
손실 타깃 변경이라 E0-C·E0-D(가중치)·E0-E(Focal)와 같은 축입니다. E0 위에 올리면 그
4자 비교에 합류하지만 E0-G 위에서는 E0-G와만 비교됩니다. ③ E0-G는 세 지표 전부 E0과
통계적으로 구분되지 않아(Welch t = +0.40 / −0.92 / −1.42, n=5+5) baseline 승격 근거가
없습니다.

E0-G가 반증한 것은 학습 일정의 **주효과**입니다. 평범한 CE 손실 아래에서 일정만 바꿨기
때문에, "손실을 바꾸면 늘어난 epoch이 값을 한다"는 **상호작용**은 아직 검정되지
않았습니다. E0 위의 E0-H가 실패하면 그때 E0-G 일정으로 승격하는 실험이 바로 그 검정이
되므로, 이 순서는 질문을 버리는 것이 아니라 뒤로 미루는 것입니다. 따라서 일정은 E0의
lr 1e-4 · patience 20 · 감쇠 없음을 그대로 상속하고, E0과의 차이는 타깃 인코딩 하나로
귀속되며 비교도 E0 대비로 기록합니다.

클래스 가중치는 쓰지 않습니다. `CrossEntropyLoss(weight=)`는 표본의 *하드* 라벨에
가중하는데, 타깃이 여러 등급에 퍼지면 "클래스별 손실 질량"이라는 의미가 사라지기
때문입니다. 조합을 조용히 재해석하지 않도록 `make_objective`가 거부하며, Focal과의
조합과 CORAL head 위의 SORD도 같은 이유로 거부합니다.

`target_encoding`과 `sord_alpha`도 재현성 identity에 포함되지만, `one_hot`을 벗어난
프로토콜에서만 `configuration`에 기록됩니다. α가 크면 one-hot, 작으면 균등 분포로
수렴하므로 α 값마다 별도 프로토콜입니다. 기존 프로토콜의 `configuration_sha256`은
바뀌지 않고 완료된 seed도 그대로 재사용됩니다.

### E0-I 라벨 신뢰도 커리큘럼

E0-I는 E0의 5개 seed가 같은 클립에 내린 예측의 합의를 라벨 신뢰도 근사로 사용합니다.
5개가 모두 같은 등급을 예측하면 `reliable`, 하나라도 갈리면 `ambiguous`입니다. 먼저 E0
체크포인트와 feature manifest의 SHA-256을 검증한 뒤 Train/Validation만 다시 추론합니다.
Test는 분석과 go/no-go 판정에서 제외됩니다.

```bash
uv run python -m zani_ai engagement analyze-label-reliability \
  --features <features> \
  --baseline-output <e0-output> \
  --output <reliability-output> \
  --device cuda
```

분석 디렉터리에는 다음 파일이 원자적으로 기록됩니다.

- `reliability_manifest.json`: seed별 logits·예측, vote entropy, 라벨/분할 분포, 입력 SHA,
  go/no-go 조건과 판정
- `clips.csv`: 클립별 신뢰도와 seed별 예측
- `report.md`: 불일치 규모, 인접 오류 관계, 지표 상한, VLM Accepted/Rejected와의 차이

`go`는 다음 다섯 조건을 모두 만족해야 합니다: 네 라벨 모두 신뢰 Train 클립 보유,
Train/Validation 모두 ambiguous 클립 보유, Validation ambiguous 오류율이 reliable의 1.5배
이상, ambiguous 제외 시 ensemble macro-F1과 QWK가 각각 2.0%p 이상 상승. 하나라도 실패하면
`no-go`이며 E0-I 학습 명령은 해당 manifest를 거부합니다.

`go`일 때 E0-I는 신뢰 Train 클립만 one-hot CE로 10 epoch 선학습한 뒤 전체 Train을
합류합니다. 두 번째 단계에서 reliable은 one-hot을 유지하고 ambiguous만 정답 확률 0.8,
인접 등급 총확률 0.2를 사용합니다. 가운데 등급은 양옆에 0.1씩, 끝 등급은 유일한 이웃에
0.2를 주므로 E0-H SORD처럼 끝 등급의 정답 질량이 더 커지지 않습니다. optimizer는 이어
쓰지만 조기 종료와 최종 checkpoint 선택은 두 번째 단계에서 새로 시작합니다.

```bash
uv run python -m zani_ai engagement reproduce-e0i \
  --features <features> \
  --reliability <reliability-output>/reliability_manifest.json \
  --output <e0i-output> \
  --device cuda

uv run python -m zani_ai engagement finalize-e0i \
  --features <features> \
  --output <e0i-output> \
  --device cuda

uv run python scripts/compare_protocols.py --baseline <e0-output> --variant <e0i-output>
```

baseline과 variant는 같은 기계에서 학습한 산출물이어야 합니다. 다르면
`compare_protocols.py`가 경고를 찍고, 그 차이에는 프로토콜 효과와 런타임이 섞입니다.

identity에는 `curriculum=label_reliability_v1`, warmup 10 epoch,
`ambiguous_target_encoding=adjacent_smoothing`, `ambiguous_neighbor_mass=0.2`가 들어갑니다.
`inputs.label_reliability`에는 manifest 경로·크기·SHA-256이 기록되며 병렬 seed record도 같은
SHA를 검증합니다. E0-I 해시는 cpu `2b1c6bc1…`, cuda `0da85a5f…`입니다.

### E0-J 결측 프레임 zero placeholder

E0-J는 PriorNet(arXiv:2605.03615)의 결측 프레임 처리 결정을 E0에 적용합니다. 논문은 얼굴을
찾지 못한 프레임을 제거하거나 coverage 비율로 요약하지 않고, 고정된 시간 슬롯의 all-zero
RGB frame으로 남겼습니다. 이에 맞춰 E0-J도 별도 coverage 채널을 추가하지 않습니다. 각
0.5초 세그먼트의 5개 슬롯 중 `valid_mask=false`인 슬롯을 49D zero vector로 두고, 유효 슬롯과
함께 mean/std를 계산합니다. 출력 shape은 기존과 같은 `[20, 98]`이며 schema만
`mediapipe_98_placeholder_v1`로 분리됩니다.

브라우저와 맞춘 총 70 유효 프레임 및 세그먼트별 3 유효 프레임 gate는 그대로입니다. 따라서
clean raw cache의 10,215개 NPZ만 사용하며 원본 영상 추출이나 MediaPipe 재실행은 필요하지
않습니다.

```bash
uv run python -m zani_ai engagement build-features --data-root datasets/raw/engagenet --raw-root datasets/processed/engagenet/raw_frames_v1_clean/raw_frames_v1 --output datasets/processed/engagenet/e0j-placeholder --schema mediapipe_98_placeholder_v1 --sample-fps 10
uv run python -m zani_ai engagement reproduce-e0j --features datasets/processed/engagenet/e0j-placeholder --output artifacts/engagement/e0j-placeholder --device cuda
uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-clean --variant artifacts/engagement/e0j-placeholder --split validation --minimum-accuracy-gain 0.02
```

5개 seed의 평균 Validation accuracy가 E0-clean보다 2.0%p 이상 높으면 성공입니다. 이 판정은
Test를 보지 않고 내립니다. 성공 여부를 기록한 뒤 아래처럼 고정 checkpoint로 Test를 한 번만
평가합니다.

```bash
uv run python -m zani_ai engagement finalize-e0j --features datasets/processed/engagenet/e0j-placeholder --output artifacts/engagement/e0j-placeholder --device cuda
uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-clean --variant artifacts/engagement/e0j-placeholder
```

feature schema가 달라도 두 manifest가 같은 raw manifest SHA와 동일한 split/clip 집합을
가리키면 비교기는 유효한 비교로 취급합니다. raw 모집단이나 실행 환경이 다르면 경고합니다.

### E0-K 학습 일정 정정

E0-K는 학습 일정에 대한 두 번째 시도이며, E0-G가 갔어야 할 방향으로 학습률을 옮깁니다.
문제는 E0-C 이래 그대로입니다. patience 20 아래에서 이 계열의 `best_epoch`은 0~11에
머물러, **어떤 프로토콜도 사실상 학습된 적이 없습니다** — 손실·샘플러·타깃 변경은 전부
결정 경계를 재형성하기 전에 멈춘 모델 위에서 측정됐습니다. E0-G가 이 문제를 겨냥했지만
lr을 1e-5로 **10배 더 낮춰** 방향이 반대였고, Validation 66.93%(E0 66.65%)는 세 지표
모두 통계적으로 구분되지 않았습니다. lr 축의 **위쪽 절반은 아직 검정되지 않았습니다.**

여기서 되돌릴 Transformer 계열의 문헌 일정은 존재하지 않습니다. E0이 적응한 EngageNet
논문(arXiv:2302.00431)은 Table 2에 레이어·유닛·활성함수·드롭아웃만 싣고 optimizer,
학습률, batch size, epoch 수를 **논문 어디에도 적지 않았습니다.** 대신 그 논문이 주는
것은 목표치입니다 — Table 4의 Gaze + Head Pose + AU Transformer가 Validation 69.10% /
Test 67.61%이고, 우리는 66.65%입니다.

따라서 lr 1e-3 / 300 epoch / 100마다 ×0.1은 arXiv:2403.17175, 즉 **E1이 재현하는 ST-GCN
논문에서 빌려온 값**이며 아키텍처 가족이 다릅니다. 재현이 아니라 실측된 병리에 대한
처방으로 들어옵니다. `batch_size`가 그 논문의 16이 아니라 **E0의 32로 남는 이유도
같습니다.** 그래프 합성곱을 위해 고른 batch는 이 Transformer에 대해 아무것도 말해주지
않고, 32로 고정해 두면 E0(1e-4)·E0-G(1e-5)·E0-K(1e-3)가 하나의 lr 축 위에 놓여 셋이
직접 비교됩니다.

| | E0 | E0-G | E0-K |
| --- | --- | --- | --- |
| `learning_rate` | 1e-4 | 1e-5 | **1e-3** |
| `maximum_epochs` | 200 | 200 | **300** |
| `patience` | 20 | 200 (해제) | **300 (해제)** |
| `lr_step` | 없음 | 100 (1회 감쇠) | **100 (100·200 2회)** |
| `batch_size` | 32 | 32 | 32 |
| schema·model·손실·seed | — | E0와 동일 | E0와 동일 |

`patience == max_epochs`가 조기 종료를 끕니다(E1-A 선례). `lr_step` 100에 300 epoch이므로
감쇠가 100과 200에서 두 번 발생합니다. 200 epoch 예산이라 감쇠 여지가 한 번뿐이던 E0-G와
달리 레시피가 끝까지 돕니다.

E0-K는 E0의 특징을 그대로 쓰므로 `build-features`를 다시 돌릴 필요가 없습니다.
`--features`는 E0-clean이 쓴 것과 같은 특징 루트(`manifest.json`이 놓인 디렉터리)를
가리켜야 합니다. 비교기가 두 manifest의 SHA-256을 대조하므로, 다른 루트를 주면
비교가 무효로 표시됩니다.

```bash
uv run python -m zani_ai engagement reproduce-e0k --features datasets/processed/engagenet --output artifacts/engagement/e0k-schedule --device cuda
uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-clean --variant artifacts/engagement/e0k-schedule --split validation
```

판정은 두 가지를 함께 봅니다. 첫째, `summary.json`의 seed별 `best_epoch`이 조기 종료
한계에 걸리지 않아야 합니다 — 조기 종료를 껐으므로 관심사는 반대쪽이며, `best_epoch`이
299에 붙어 있으면 300 epoch도 부족했다는 뜻이라 예산을 늘려 재실행합니다. 둘째, E0-clean
대비 Validation 비교표입니다. 이 판정은 Test를 보지 않고 내리며, 기록한 뒤에만 고정
checkpoint로 Test를 한 번 평가합니다.

```bash
uv run python -m zani_ai engagement finalize-e0k --features datasets/processed/engagenet --output artifacts/engagement/e0k-schedule --device cuda
uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-clean --variant artifacts/engagement/e0k-schedule
```

lr을 10배 올렸으므로 발산할 수 있습니다. 손실이 NaN이 되거나 Validation Macro-F1이 초기
epoch부터 회복하지 못하면 초기 lr과 감쇠 시점을 조정하는데, 이 둘은 재현성 identity에
들어가므로 **조정한 값은 E0-K가 아니라 새 프로토콜**이 됩니다. 조정 과정과 기각된 값을
여기에 남깁니다.

학습 시간은 약 12배입니다. E0-clean의 `best_epoch`은 [4, 2, 3, 11, 7]이고 각 seed가
patience 20을 더 돈 뒤 멈추므로 5 seed 합계가 약 127 epoch인데, E0-K는 300 × 5 =
1500 epoch을 전부 돕니다.

seed를 여러 개 동시에 돌리는 것은 **카드가 여러 장일 때만** 의미가 있습니다. L40S
한 장에서 E1을 돌린 실측이 utilization 98~99% / 311W(350W 중)로 compute bound라,
같은 카드에 seed를 쌓으면 시간만 나눠 쓰고 총 시간은 줄지 않습니다
([../.agents/ai-remote-l40s-guide.md](../.agents/ai-remote-l40s-guide.md) 참고).
메모리는 병목이 아닙니다 — E1 기준 5,095 / 46,068 MiB만 씁니다.

이 길이면 JupyterHub idle culler(24시간)에 걸릴 수 있습니다. 완료된 seed는 재사용되므로
같은 명령을 다시 실행하면 이어서 진행되며, 잃는 것은 많아야 seed 하나 분량입니다.

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

## 추론 속도 실측

`bench.html`은 내보낸 ONNX의 추론 지연을 execution provider별로 잽니다. 합성 입력을
쓰므로 특징 파이프라인을 앱에 배선하지 않고도 어떤 모델이든 측정할 수 있고, provider가
세션 생성에 실패하면 그 자체가 결과입니다 — 해당 백엔드가 지원하지 않는 연산자가
그래프에 있다는 뜻입니다.

측정할 모델을 `public/models/bench/`에 `<이름>.onnx`와 `<이름>.metadata.json` 쌍으로
둡니다. 이 파일들은 Git에 포함되지 않습니다.

```bash
cd web/engagement-demo
mkdir -p public/models/bench
cp ../../artifacts/engagement/e0/seed-42/onnx/engagement.onnx public/models/bench/e0.onnx
cp ../../artifacts/engagement/e0/seed-42/onnx/engagement.metadata.json public/models/bench/e0.metadata.json
cp ../../artifacts/engagement/e1/seed-42/onnx/engagement.onnx public/models/bench/e1.onnx
cp ../../artifacts/engagement/e1/seed-42/onnx/engagement.metadata.json public/models/bench/e1.metadata.json
npm ci
npm run dev
```

콘솔에 표시된 주소 뒤에 `/bench.html`을 붙여 엽니다. **`file://`로 직접 열면 동작하지
않습니다** — TypeScript 변환과 모델 fetch 모두 개발 서버가 필요합니다.

데모는 10초 창을 1초마다 갱신하므로 추론 예산은 1초 미만이고, MediaPipe도 같은 1초를
나눠 씁니다.

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
