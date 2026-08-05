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
선택합니다. seed는 `42,43,44,45,46`이며 다음 명령으로 정확한 E0 설정을 실행합니다.

E0\~E0-L과 E1\~E1-B는 이 5개 seed로 고정돼 있습니다. **새로 추가되는 프로토콜의 기본값은
10개**이고 비교 기준선도 E0-10으로 옮겨갑니다 — 근거와 이행 방법은
[seed 수와 검출력](#seed-수와-검출력)에 있습니다.

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
모두 통계적으로 구분되지 않았습니다. lr 축의 위쪽 절반이 검정되지 않은 상태였고, E0-K가
그것을 검정합니다. 결론은 아래 [결과 — 기각](#결과--기각)에 있습니다. **전제가 틀렸다는
쪽으로 결론이 났으므로**, 이 절의 동기 서술은 실행 당시의 사전등록 근거로 읽으십시오.

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

#### 결과 — 기각

5-seed 완주 후 고정 checkpoint로 Test를 한 번 평가했습니다. 특징 manifest SHA-256과
실행 환경이 E0-clean과 같으므로 비교는 유효합니다(비교기가 경고를 내지 않았습니다).

Validation (선택 지표, n=5+5):

| 지표 | E0-clean | E0-K | 차이 | Welch t | p |
| --- | --- | --- | --- | --- | --- |
| accuracy | 0.6665 ± 0.0165 | 0.6520 ± 0.0150 | −1.45%p | −1.45 | 0.184 |
| macro-F1 | 0.5745 ± 0.0132 | 0.5697 ± 0.0232 | −0.48%p | −0.40 | 0.704 |
| QWK | 0.7060 ± 0.0167 | 0.6874 ± 0.0241 | −1.86%p | −1.42 | 0.198 |
| within-1 | 0.9598 ± 0.0033 | 0.9584 ± 0.0057 | −0.14%p | −0.49 | 0.641 |

Test (확인용, n=5+5):

| 지표 | E0-clean | E0-K | 차이 | Welch t | p |
| --- | --- | --- | --- | --- | --- |
| accuracy | 0.7098 ± 0.0093 | 0.6813 ± 0.0348 | −2.85%p | −1.77 | 0.142 |
| macro-F1 | 0.5905 ± 0.0120 | 0.5762 ± 0.0124 | −1.43%p | −1.86 | 0.100 |
| QWK | 0.7839 ± 0.0167 | 0.7440 ± 0.0287 | **−3.99%p** | −2.68 | **0.034** |
| within-1 | 0.9401 ± 0.0078 | 0.9333 ± 0.0133 | −0.68%p | −0.98 | 0.363 |
| 인접 오류 비중 | 0.7930 ± 0.0300 | 0.7883 ± 0.0503 | −0.47%p | −0.18 | 0.863 |

오분류 총계는 2,919 → 3,206으로 늘었습니다. Test QWK만 p < 0.05로 유의하게 하락했고
나머지는 유의하지 않지만, **아홉 지표의 방향이 전부 아래**입니다.

**완료 조건 ②가 예상과 반대로 확인됐습니다.** `best_epoch`이 1, 4, 2, 2, 0입니다 — 300
epoch 예산 중 첫 5 epoch 안이고, seed 46은 첫 epoch이 최고점입니다. E0-clean의 5, 3, 4,
12, 8보다 오히려 **앞으로 갔습니다.** 조기 종료 한계에 걸리지 않은 것은 맞지만, 그 확인이
말해주는 것은 **patience 20이 애초에 아무것도 자르고 있지 않았다**는 사실입니다.

감쇠도 값을 하지 않았습니다. epoch 100·200의 ×0.1이 실제로 발생했고, seed 42의 macro-F1
구간 평균은 0.3905(11–100) → 0.3965(101–200) → 0.4087(201–300)으로 노이즈 수준입니다.
어느 seed도 peak를 회복하지 못했습니다. "감쇠 시점에 도달하지 못했다"는 문제 제기는
도달시켜서 답했고, 답은 도달해도 무의미하다는 것입니다.

발산(NaN)은 없었지만 peak 후 붕괴합니다. 최종 epoch이 peak보다 −0.146~−0.195 낮고
(E0-clean은 −0.014~−0.082), 300 epoch 중 약 296이 낭비입니다. checkpoint를 Validation
최고점에서 뽑으므로 최종 지표는 보호되지만, lr 1e-3이 이 모델을 불안정하게 만든다는
증거입니다.

**기각합니다.** 12배 비용으로 유의한 개선이 없고 방향은 일관되게 아래입니다. 목표였던
Transformer 기준선(Validation 69.10%)과의 2.45%p 격차 중 **학습 일정이 설명하는 몫은
없습니다.**

lr을 조정해 재시도하는 경로는 열려 있지만, lr·감쇠 시점은 재현성 identity에 들어가므로
**조정한 값은 E0-K가 아니라 새 프로토콜**이 됩니다. 다만 권하지 않습니다. lr
1e-5(E0-G)·1e-4(E0)·1e-3(E0-K) 세 점에서, 그중 둘은 조기 종료를 끈 채로, 모두 초반에
peak를 칩니다. 100배 범위를 훑고 같은 답이 나왔으므로 **일정 축은 닫힌 것으로 봅니다.**
남은 후보는 용량·특징·라벨 잡음입니다.

한 가지 유보: E0-K는 lr·epoch·patience를 함께 옮겼으므로 "lr 1e-4를 유지한 채 patience만
해제"는 엄밀히는 미검정입니다. E0-G가 lr 1e-5에서 그것을 했고 `best_epoch`이 13, 6, 13,
9, 22로 여전히 초반이었으므로 그 조합만 다를 가능성은 낮다고 판단했습니다.

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
300프레임을 전부 쓰는데 우리는 10fps로 3프레임 중 하나만 씁니다.

**이전 판의 "Table 5는 2프레임마다로 0.7124 → 0.6813, 3.1%p 하락"은 틀린 비교였습니다.**
해당 표는 Table 3이고, 0.7124는 **ordinal** 모델의 headline이며 0.6813은 **non-ordinal**
모델을 2프레임마다로 성기게 한 값입니다. 서로 다른 모델을 비교한 숫자입니다. non-ordinal
기준으로 다시 읽으면 이렇습니다.

| 프레임 선택 | Validation accuracy |
|---|---|
| 전체 프레임 | 0.6937 |
| 2프레임마다 | 0.6813 |
| 4프레임마다 | 0.6907 |
| 8프레임마다 | 0.6907 |
| 16프레임마다 | 0.6841 |

곡선이 단조롭지 않습니다. 4·8프레임마다가 2프레임마다보다 **높습니다.** 우리 10fps는
3프레임마다에 해당해 0.6813~0.6907 구간에 놓이므로, 시간 해상도로 설명되는 폭은 전체
프레임 대비 최대 1.2%p입니다. 논문 본문도 "a trade-off between accuracy and computation"
이라고만 적습니다. **30fps 작업은 문헌을 그대로 재현하기 위한 것이고, 남은 정확도 격차의
주원인이라는 근거는 없습니다.**

`SAMPLE_FPS`가 **추출 단계에서** 프레임을 버리므로 `raw_frames_v1`로는 300스텝을 만들
수 없습니다. 원본 영상에서 30fps로 다시 추출해야 합니다. `sample_fps`는 해시되는
extraction fingerprint에 포함되어 있어 rate가 다른 캐시가 조용히 섞이지 않고,
10fps는 `raw_frames_v1`/`landmark_78_v1` 이름을 그대로 유지하므로 기존 캐시와
체크포인트가 살아 있습니다.

`--keep-low-coverage`와 `--max-excluded-fraction 0.1`을 반드시 함께 넘깁니다. 둘 다
실측에서 나온 값입니다. 30fps 추출은 구간 커버리지 규칙에 865클립(7.72%)이 걸리는데
기본 임계값이 0.05라서 `exclusion_threshold_exceeded`로 죽습니다 — 같은 규칙에서
10fps clean 캐시는 8.84%를 제외하고도 0.1 기준으로 통과했습니다. **30fps가 제외를 더
적게 하는데 더 엄격한 기준으로 재진 것입니다.** 최소 3장이 fps와 무관한 절대값이라
구간당 기대 프레임이 5장인 10fps에서는 60%를, 15장인 30fps에서는 20%를 요구합니다.

`--keep-low-coverage`는 그 규칙 자체를 끕니다. arXiv:2403.17175 §5가 "samples with
occluded or absent faces, i.e., no facial landmarks"를 Not-Engaged로 분류한다고 보고하므로
논문은 규칙이 떨어뜨리는 클립들을 학습에 썼고, 그 클립은 대부분 Not-Engaged입니다 —
문헌 대비 Train 부족분 748건 중 496건이 그 한 클래스에서 나온 이유입니다. 정책은
extraction fingerprint에 함께 해시되므로 두 정책의 캐시가 섞이지 않습니다. 디코드 실패와
0프레임 클립은 정책과 무관하게 계속 제외됩니다.

```bash
uv run python -m zani_ai engagement extract-raw \
  --data-root datasets/raw/engagenet \
  --face-landmarker-model models/face_landmarker.task \
  --output datasets/processed/engagenet \
  --sample-fps 30 --workers 64 \
  --keep-low-coverage --max-excluded-fraction 0.1 --progress-every 200

uv run python -m zani_ai engagement build-features \
  --data-root datasets/raw/engagenet \
  --raw-root datasets/processed/engagenet/raw_frames_30fps_v1 \
  --output datasets/processed/engagenet/e1b \
  --schema landmark_78_300_v1 --sample-fps 30
```

> ⚠️ **두 번째 명령은 아직 돌지 않습니다.** `build_feature_manifest`가 raw manifest의
> schema를 `raw_frames_v1`과 정확히 비교해(`representations.py`) `raw_frames_30fps_v1`을
> 거부하고, provenance에 `EXPECTED_FRAME_COUNT`(100)와 `MINIMUM_VALID_FRAMES`를 상수로
> 박습니다. raw manifest의 provenance에서 읽어오도록 고쳐야 하며, 추출이 도는 동안 하면
> 됩니다. 첫 명령은 이 제약과 무관하게 정상 동작합니다.

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

### E0-L 순서형 K-1 이진 헤드

E0-L은 등급 순서를 K-1 = 3개의 **독립** 이진 결정으로 분해합니다. 문헌은 이 분해로 ST-GCN
기준 69.37% → 71.24%(+1.87%p)를 보고합니다. 순서 구조를 쓴 기존 시도 둘과는 다른 지점을
건드립니다.

- **E0-B(CORAL)** 는 가중치 벡터 하나를 세 임계값이 공유하고 bias만 따로 둡니다. 그래서
  누적 로짓이 구조적으로 단조이고, 독립 헤드가 서로 어긋날 여지가 없습니다. macro-F1 0.5185.
- **E0-H(SORD)** 는 softmax head를 두고 손실의 *타깃*만 이웃 등급으로 퍼뜨립니다. Validation
  정확도 67.58%(우리 최고) 대비 macro-F1 56.80%(우리 최저).

따라서 미검정으로 남은 조합은 **독립성 + 동결 백본**이고, E0-L은 그 밖의 것을 바꾸지
않습니다. 헤드는 E0 분류기와 같은 모양(`Linear(256→128)→ReLU→Dropout(0.3)→Linear(128→1)`)의
독립 3벌이며, 2단계 일정은 E0의 lr 1e-4 · 200 epoch · patience 20 · Validation Macro-F1
선택을 그대로 상속합니다.

**1단계는 재학습하지 않습니다.** `--stage1`이 완주한 E0 출력 디렉터리를 가리키고 seed n이
seed n의 `best.pt`를 씁니다. E0-I가 reliability manifest를 `inputs`에 지문화한 선례를 따라
seed별 체크포인트의 경로·크기·SHA-256이 `inputs.stage1.seeds`에 기록되고, 그 map의 정규
해시가 `inputs.stage1.sha256`이 되어 다른 1단계를 가리킨 재개는 거부됩니다. 학습 전후와
seed 완료 기록 직전에 파일이 그대로인지 다시 검사합니다.

동결 범위는 `input_projection`·`position_embedding`·`encoder`와 정규화 통계입니다.
gradient를 끄는 것으로 끝내지 않고 **백본을 `eval` 모드에 고정**합니다. `model.train()`이
encoder dropout을 켜면 표현이 배치마다 흔들리고, 그렇게 맞춘 헤드는 동결 백본에 맞춘 헤드가
아니기 때문입니다. optimizer도 헤드 파라미터만 받습니다.

적재한 정규화 통계가 이번 Train 분할에서 재계산한 값과 어긋나면 실행을 거부합니다. 통계는
Train 분할만의 함수이므로, 어긋난다는 것은 그 백본이 이 데이터셋을 본 적이 없다는 뜻입니다.

#### 비단조 처리 규칙

독립 헤드는 `P(y>0) ≥ P(y>1) ≥ P(y>2)`를 보장하지 않습니다. 규칙은 **누적 최소 보정**입니다.
`p = sigmoid(z)`에 `p̃_j = min(p_0..p_j)`를 적용해 단조 원뿔로 투사하고, 인접 차
`[1-p̃₀, p̃₀-p̃₁, p̃₁-p̃₂, p̃₂]`를 클래스 확률로 씁니다. `p̃`가 비증가이므로 차는 구조적으로
음수가 아니고 합이 1이며, clamp와 정규화는 float 오차만 흡수합니다.

`torch.cummin` 대신 정적인 임계값 개수만큼 펼친 루프로 씁니다. cummin에는 대응하는 ONNX
연산자가 없어 `Scan`으로 내려가지만, 펼친 형태는 `Min` 노드 사슬이 되어 배포 그래프에
반복문이 남지 않습니다. batch는 양쪽 모두 유일한 동적 축입니다.

보정 **전** 위반율은 `metrics.json`의 `validation`·`test` 블록에
`monotonicity_violation_rate`로 남습니다. 다른 head에서는 `null`이라 "인접 쌍이 없는
프로토콜"과 "위반이 없었던 프로토콜"이 구분됩니다.

디코딩은 CORAL의 `count(p > 0.5)`가 아니라 **보정된 확률의 argmax**입니다. 브라우저는
내보낸 벡터에 softmax를 한 번 더 걸고 argmax하며 softmax는 순서를 보존하므로, 배포 시
예측 라벨은 곧 그 벡터의 argmax입니다. 두 규칙은 `p̃ = (0.9, 0.6, 0.4)`에서 각각 2와 3으로
갈리므로, 랭크를 택하면 측정한 숫자가 배포된 동작이 아니게 됩니다.

`monotonicity`와 `decoding`이 재현성 identity에 들어가는 이유가 이것입니다. 둘 다 고정된
가중치가 내는 숫자를 바꾸므로 프로토콜의 일부입니다. E0-L 해시는 cpu `76c7321e…`,
cuda `2c6c20aa…`이며, 기본값을 벗어난 프로토콜에서만 기록되므로 기존 15개 프로토콜의
`configuration_sha256`은 바뀌지 않습니다.

#### 실행

E0-L은 E0의 특징을 그대로 쓰므로 `build-features`를 다시 돌릴 필요가 없습니다. `--features`와
`--stage1`은 같은 실행이 남긴 것이어야 합니다 — 통계 검사가 이를 강제하고, 비교기도 두
manifest의 SHA-256을 대조합니다.

```bash
uv run python -m zani_ai engagement reproduce-e0l \
  --features datasets/processed/engagenet \
  --stage1 artifacts/engagement/e0-clean \
  --output artifacts/engagement/e0l-ordinal-binary \
  --device cuda

uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-clean --variant artifacts/engagement/e0l-ordinal-binary --split validation
```

Validation 비교를 기록한 뒤에만 고정 checkpoint로 Test를 한 번 평가합니다.

```bash
uv run python -m zani_ai engagement finalize-e0l \
  --features datasets/processed/engagenet \
  --output artifacts/engagement/e0l-ordinal-binary \
  --device cuda

uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-clean --variant artifacts/engagement/e0l-ordinal-binary
```

seed는 순차로 돕니다. 동결 백본이라도 forward는 encoder 전체를 지나므로 L40S 한 장에서는
여전히 compute bound이고, 같은 카드에 seed를 쌓으면 총 시간이 줄지 않습니다
([../.agents/ai-remote-l40s-guide.md](../.agents/ai-remote-l40s-guide.md) 참고).

한 가지 유보: 현 SOTA(PriorNet)는 순서 구조 없이 73.58%를 냅니다. 순서 구조를 쓴 우리 시도
둘이 모두 기준선을 넘지 못했으므로 이 방향의 상한은 크지 않을 수 있습니다.

#### 결과 — 기각

5-seed 완주 후 고정 checkpoint로 Test를 한 번 평가했습니다. 특징 manifest SHA-256
(`08dcbff3…`)과 실행 환경이 E0-clean과 같고 1단계도 그 산출물이므로 비교는 유효합니다
(비교기가 경고를 내지 않았습니다).

Validation (선택 지표, n=5+5):

| 지표 | E0-clean | E0-L | 차이 | Welch t | p |
| --- | --- | --- | --- | --- | --- |
| accuracy | 0.6665 ± 0.0165 | 0.6688 ± 0.0171 | +0.22%p | +0.21 | 0.838 |
| macro-F1 | 0.5745 ± 0.0132 | 0.5944 ± 0.0162 | +2.00%p | +2.14 | 0.067 |
| QWK | 0.7060 ± 0.0167 | 0.7176 ± 0.0220 | +1.16%p | +0.94 | 0.377 |
| within-1 | 0.9598 ± 0.0033 | 0.9655 ± 0.0060 | +0.57%p | +1.87 | 0.110 |

Test (확인용, n=5+5):

| 지표 | E0-clean | E0-L | 차이 | Welch t | p |
| --- | --- | --- | --- | --- | --- |
| accuracy | 0.7098 ± 0.0093 | 0.7003 ± 0.0110 | **−0.95%p** | −1.48 | 0.177 |
| macro-F1 | 0.5905 ± 0.0120 | 0.5916 ± 0.0075 | +0.11%p | +0.17 | 0.870 |
| QWK | 0.7839 ± 0.0167 | 0.7934 ± 0.0080 | +0.95%p | +1.14 | 0.298 |
| within-1 | 0.9401 ± 0.0078 | 0.9485 ± 0.0032 | +0.84%p | +2.24 | 0.072 |
| 인접 오류 비중 | 0.7930 ± 0.0300 | 0.8279 ± 0.0148 | +3.48%p | +2.33 | 0.060 |

오분류 총계는 2,919 → 3,015로 늘었습니다.

**기각합니다.** 사전등록된 게이트는 Validation accuracy +2.00%p인데 실측이 +0.22%p이고,
Test에서는 −0.95%p로 방향이 반대입니다. Validation macro-F1 +2.00%p(p=0.067)는 Test에서
+0.11%p(p=0.870)로 사라졌으므로 Validation 특이적인 이득으로 봅니다. 문헌이 보고한
accuracy +1.87%p는 재현되지 않았습니다.

비교기의 Test 판정줄은 "성공 — macro-F1과 QWK 동시 상승 조건"을 출력합니다. 그 기준은
방향만 보고 유의성을 보지 않으므로, p=0.870인 +0.11%p를 채택 근거로 쓸 수 없습니다.
**판정줄과 이 기록이 다르면 이 기록이 판단입니다.**

#### 무엇을 배웠는가

**정확도를 순서 품질과 맞바꿉니다.** 오류는 늘었지만 남은 오류가 대각선에 더 붙었습니다.
혼동 행렬이 그 교환을 그대로 보여줍니다. 다수 클래스(Highly-Engaged) 정답이 4,929 → 4,766
으로 163개 줄고 그중 141개가 Engaged로 갔습니다. 대신 Engaged 정답이 876 → 954(+78),
Barely-Engaged가 333 → 380(+47)입니다.

E0-C~E0-F가 가중치와 샘플러로 실패했던 "다수 클래스 쪽으로 밀린 경계"를 순서형 분해는
실제로 움직였습니다. 옮긴 질량이 정답이 되지 못하고 인접 오류로 남은 것이 실패 지점이며,
이는 손실 가중치 계열의 실패(경계가 아예 움직이지 않음)와 다른 실패입니다.

**보정이 결과를 만든 것이 아닙니다.** 보정 전 단조성 위반율은 seed별로 0.77%, 0.97%, 0.77%,
2.14%, 4.29%입니다. 독립 헤드가 등급 순서를 대체로 스스로 학습했고 누적 최소 보정은 인접
쌍의 1~4%에서만 개입했습니다. 위반율이 절반에 달했다면 개선을 헤드가 아니라 후처리에
귀속시켜야 했을 텐데 그 경우가 아닙니다.

**seed 분산이 일관되게 작습니다** — macro-F1 sd 0.0120 → 0.0075, QWK 0.0167 → 0.0080,
within-1 0.0078 → 0.0032. 백본이 동결되어 seed마다 변하는 것이 헤드뿐이므로 예상되는
방향입니다.

`best_epoch`은 2, 18, 4, 3, 9입니다. seed 43이 18까지 간 것은 이 계열에서 처음이고, 어느
seed도 200 epoch 예산에 닿지 않았으므로 일정이 병목도 아니었습니다.

순서 구조를 쓴 시도는 이것으로 셋(E0-B CORAL, E0-H SORD, E0-L)이고 모두 accuracy 기준을
넘지 못했습니다. **순서 구조 축은 닫힌 것으로 봅니다.** 남은 후보는 용량·특징·라벨
잡음이며, 순서 품질(QWK·within-1)을 목표로 삼는 결정이 내려진다면 그때 이 결과가 근거가
됩니다.

> **이 결론은 S15P11A105-289에서 뒤집혔습니다.** 마지막 문장의 "순서 품질을 목표로 삼는
> 결정"이 실제로 내려졌고, 그러자 같은 순서형 head가 [E0-M](#e0-m-공유-백본-dual-head)에서
> 게이트를 통과했습니다. 닫혀 있던 것은 순서 구조가 아니라 판정 기준이었습니다. 위 문단은
> 그 시점의 5-seed 근거로 내려진 판단으로 읽으십시오.

### E0-M 공유 백본 dual head

E0-M은 E0-10의 softmax head와 E0-L의 순서형 head를 **encoder 하나 위에** 얹고 두 head의
클래스 확률을 섞습니다. 목적 지표가 4-class 정확도가 아니라 제품이 실제로 쓰는
**저참여 판정**이기 때문입니다.

#### 왜 섞는가 — 둘은 서로 다른 것을 잘합니다

Test 실측(E0-10 10-seed, E0-L 5-seed)에서 두 프로토콜은 반대 방향으로 갈립니다.

| 지표 | E0-10 | E0-L | 차이 |
| --- | --- | --- | --- |
| accuracy | 0.7136 ± 0.0083 | 0.7003 ± 0.0110 | **−1.33%p** |
| macro-F1 | 0.5866 ± 0.0097 | 0.5916 ± 0.0075 | +0.51%p |
| 저참여 recall | 0.6673 ± 0.0375 | 0.7082 ± 0.0219 | **+4.09%p** |
| 저참여 FPR | 0.0560 ± 0.0145 | 0.0665 ± 0.0059 | +1.05%p |

E0-L은 실제로 헤매는 학생을 4.09%p 더 많이 잡고, 그 대가로 4-class 정확도 1.33%p와
오탐 1.05%p를 냅니다. 지금까지의 모든 프로토콜은 macro-F1으로 순위를 매겼으므로 **이
교환은 한 번도 선택의 대상이 아니었습니다.** 289는 이것을 선택의 대상으로 만듭니다.

둘 중 하나를 고르지 않고 섞는 이유는 **둘이 두 모델이 아니기 때문입니다.** E0-L은 이미 E0의
encoder를 동결하므로 두 head가 같은 pooled 벡터를 읽습니다. 결합 비용은 추론 두 번이 아니라
`encoder 한 번 + 작은 MLP 두 벌`이고, 따라서 런타임 근거로 하나를 고를 이유가 없습니다.
그러면 `alpha`는 근거로 정할 수 있는 값이 됩니다.

#### 무엇이 고정이고 무엇이 선택인가

1단계는 **E0-10의 완주 산출물**이고, encoder뿐 아니라 **softmax head까지 동결**합니다.
그래야 `alpha = 1`이 seed별로 E0-10 자신의 판정이 되고, 정확도 가드와 recall 이득을 두
프로토콜의 집계가 아니라 **한 실행 안에서** 잴 수 있습니다.

2단계는 E0-L과 완전히 같습니다 — K-1개 `1[y>j]` 지시자에 대한 BCE, lr 1e-4, 200 epoch,
patience 20, 그리고 **순서형 head의 Validation macro-F1으로 checkpoint 선택**. E0-10 seed
n의 가중치는 E0 seed n과 동일하므로([seed 목록은 수치에 영향을 주지 않습니다](#seed-목록은-수치에-영향을-주지-않습니다--실측-확인))
seed 42\~46은 E0-L의 head를 재현하고 47\~51이 10개로 늘립니다.

`alpha`·두 temperature·epsilon은 **사전 등록한 격자에서 Validation만 보고** 고릅니다.
격자와 선택 규칙은 재현성 identity(`MixingProtocol`)에 들어가고, 고른 점은 seed별 산출물
속성입니다. 격자를 넓히면 다른 프로토콜이 됩니다.

| 항목 | 값 |
| --- | --- |
| `alpha_grid` | 0.0, 0.25, 0.5, 0.75, 1.0 |
| `temperature_grid` | 1.0, 1.5, 2.0 (두 head 각각) |
| `epsilon` | 1e-6 |
| 실제 탐색 점 | 31개 |
| 선택 규칙 | 아래 두 예산 안에서 **Validation 저참여 recall 최대** |
| 오탐 예산 | 3연속 근사 90분당 0.5회 이하 |
| 정확도 예산 | `alpha = 1` 대비 하락 1.0%p 이하 |

`alpha` 0과 1에서는 한쪽 head만 남으므로 그 쪽 temperature는 판정을 바꾸지 않습니다.
`alpha = 1`은 두 temperature 모두, `alpha = 0`은 softmax temperature가 접혀서 31개가
됩니다. `alpha = 0`에서 순서형 temperature는 **접지 않습니다** — 클래스 확률이 독립
sigmoid의 인접 차이므로 scaling이 어느 차가 최대인지를 바꿉니다.

어느 점도 두 예산을 넘지 못하면 `alpha = 1`(기준선 판정)을 그대로 두고
`constraints_satisfied: false`로 남깁니다. 기준선 자신도 오탐 예산을 넘을 수 있으므로 이
경로는 방어용이 아니라 실제 경로입니다.

#### 출력 계약 — FE 코드는 바뀌지 않습니다

모델 출력은 `log(p_safe)`입니다.

```text
p_softmax = softmax(e0_logits / T_softmax)
p_ordinal = ordinal_to_probabilities(e0l_logits / T_ordinal)   # 누적 최소 보정 포함
p_mix     = alpha * p_softmax + (1 - alpha) * p_ordinal
p_safe    = normalize(clamp_min(p_mix, epsilon))
output    = log(p_safe)
```

브라우저는 출력을 logits로 보고 softmax를 한 번 걸므로 `softmax(output) == p_safe`이고,
결합 확률이 그대로 보존됩니다. **`web/engagement-demo`와 FE 도메인 코드는 한 줄도 바뀌지
않습니다.** 배포 metadata도 그대로입니다(`output_name: logits`, 입력 `["batch", 20, 98]`).

두 head를 로짓 단계에서 섞지 않고 각각 4-class 확률로 바꾼 뒤 섞습니다. 두 head의 로짓
스케일이 다르므로(4-class CE vs 3-임계값 BCE) 로짓 blend는 `alpha`가 스케일에 딸려 가는
값이 됩니다.

`epsilon` clamp는 `log(0)`을 막습니다. float32에서 큰 로짓의 sigmoid는 **정확히** 0 또는
1이므로 이는 가정이 아니라 도달 가능한 상태입니다. clamp가 잃는 확률 질량은 클래스당 최대
`epsilon`이고, 판정 임계값 0.35보다 네 자리 아래입니다.

클래스 순서는 두 head와 최종 출력 모두 `Not-Engaged, Barely-Engaged, Engaged,
Highly-Engaged`입니다.

#### 실행

E0-M은 E0의 특징을 그대로 쓰므로 `build-features`를 다시 돌릴 필요가 없습니다. `--stage1`은
**E0-10**의 완주 출력이어야 합니다(E0의 5-seed 출력이 아닙니다 — seed 47\~51의 checkpoint가
없어 즉시 거부됩니다).

```bash
uv run python -m zani_ai engagement reproduce-e0m \
  --features datasets/processed/engagenet \
  --stage1 artifacts/engagement/e0-10 \
  --output artifacts/engagement/e0m-dual-head \
  --device cuda

uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-10 --variant artifacts/engagement/e0m-dual-head --split validation
```

Validation 비교와 `alpha` 선택을 기록한 뒤에만 고정 checkpoint로 Test를 한 번 평가합니다.

```bash
uv run python -m zani_ai engagement finalize-e0m \
  --features datasets/processed/engagenet \
  --output artifacts/engagement/e0m-dual-head \
  --device cuda

uv run python scripts/compare_protocols.py --baseline artifacts/engagement/e0-10 --variant artifacts/engagement/e0m-dual-head
```

비교기는 4-class 표 아래에 **저참여 이진 판정 표**와 289의 채택 게이트 판정줄을 함께
출력합니다. 저참여 지표는 seed별 confusion matrix에서 유도하므로 **E0-10·E0-L의 이미 끝난
실행에도 재학습 없이 적용됩니다.**

10-seed 학습은 길고 원격 로그로만 보이므로 epoch 줄마다 타임스탬프와 남은 시간이
붙습니다. **`eta_stop`을 보십시오** — early stopping 때문에 끝은 모르는 값이라 하나로
찍지 않고 양쪽 경계를 냅니다. `eta_stop`은 지금부터 개선이 없다고 볼 때의 최단, `eta_max`는
epoch 예산을 다 쓸 때의 최장입니다. 이 계열의 실측 `best_epoch`이 2\~11이므로 실제 종료는
`eta_stop` 쪽에 붙습니다. 형식과 seed 완료 줄은
[../.agents/ai-remote-l40s-guide.md](../.agents/ai-remote-l40s-guide.md)에 있습니다.

#### 무엇이 어디에 기록되는가

| 위치 | 내용 |
| --- | --- |
| `summary.json` seed 레코드 | 고른 `alpha`·temperature·epsilon, 예산 충족 여부, 선택점·기준점의 저참여 지표와 정확도 |
| `metrics.json` `probability_mixing` | 위 + 31개 격자 점 전체(점별 accuracy·macro-F1·저참여 recall·FPR·90분 오탐), 보정 전 단조성 위반율 |
| `test_results.json` `aggregate.test_low_engagement` | Test 저참여 지표의 pooled 값과 seed별 평균·표준편차 |
| `best.pt` | `probability_mixing` — 고른 점이 가중치와 함께 이동합니다 |
| `configuration_sha256` | 격자·두 예산·선택 규칙 (고른 점은 **들어가지 않습니다**) |

고른 점이 없는 dual head 모델은 `deployment_view`가 거부합니다. `alpha`를 아무도 고르지
않은 모델은 정의된 출력이 없으므로, 기본값을 슬쩍 채워 배포되는 경로를 막았습니다.

#### 추론 예산

공유 encoder가 예산 논거의 전부입니다. E0 Transformer는 최악 조건(WASM)에서도 p95 11.2ms로
1초 예산의 1.1%이며([추론 속도 실측](#추론-속도-실측)), E0-M이 그 위에 더하는 것은 pooled
256차원 벡터에 대한 `Linear(256→128)→ReLU→Linear(128→1)` 3벌 + softmax·sigmoid·log뿐입니다.
약 10만 MAC로, 20토큰 4층 Transformer encoder 대비 무시할 수준입니다.

**단, 이 수치는 실측이 아니라 연산량 논거입니다.** 실제 브라우저 지연은 학습이 끝난 뒤
`bench.html`로 재야 합니다. 입력·출력 계약이 E0와 같으므로 절차는 그대로입니다 —
`public/models/bench/`에 `e0m.onnx`/`e0m.metadata.json` 쌍을 두면 됩니다.

#### 결과 — 채택

jupyter04 / L40S에서 10-seed를 완주하고 Validation에서 `alpha`를 고정한 뒤 고정 checkpoint로
Test를 한 번 평가했습니다. E0-10과 특징 manifest·실행 환경이 같고 1단계가 그 산출물이므로
비교는 유효합니다(비교기가 경고를 내지 않았습니다).

Validation (선택 지표, n=10+10):

| 지표 | E0-10 | E0-M | 차이 | Welch t | p | 검출한계 |
| --- | --- | --- | --- | --- | --- | --- |
| accuracy | 0.6716 ± 0.0132 | 0.6761 ± 0.0161 | +0.45%p | +0.68 | 0.504 | 1.84%p |
| macro-F1 | 0.5745 ± 0.0109 | 0.5965 ± 0.0091 | **+2.21%p** | +4.92 | 0.000 | 1.26%p |
| QWK | 0.7039 ± 0.0142 | 0.7204 ± 0.0154 | +1.65%p | +2.49 | 0.023 | 1.86%p |
| within-1 | 0.9585 ± 0.0050 | 0.9647 ± 0.0040 | +0.62%p | +3.06 | 0.007 | 0.57%p |

Test (n=10+10):

| 지표 | E0-10 | E0-M | 차이 | Welch t | p | 검출한계 |
| --- | --- | --- | --- | --- | --- | --- |
| macro-F1 | 0.5866 ± 0.0097 | 0.5992 ± 0.0062 | **+1.26%p** | +3.48 | 0.003 | 1.02%p |
| QWK | 0.7796 ± 0.0135 | 0.7919 ± 0.0061 | +1.23%p | +2.64 | 0.021 | 1.31%p |
| within-1 | 0.9384 ± 0.0061 | 0.9436 ± 0.0032 | +0.53%p | +2.43 | 0.030 | 0.61%p |
| accuracy | 0.7136 ± 0.0083 | 0.7150 ± 0.0052 | +0.14%p | +0.45 | 0.659 | 0.87%p |
| 인접 오류 비중 | 0.7844 ± 0.0244 | 0.8021 ± 0.0134 | +1.77%p | +2.01 | 0.064 | 2.47%p |

Test 저참여 이진 판정:

| 지표 | E0-10 | E0-M | 차이 | p | 검출한계 |
| --- | --- | --- | --- | --- | --- |
| 저참여 recall | 0.6673 ± 0.0375 | 0.7012 ± 0.0220 | **+3.39%p** | 0.027 | 3.86%p |
| 저참여 FPR | 0.0560 ± 0.0145 | 0.0636 ± 0.0109 | +0.75%p | 0.205 | 1.60%p |
| 저참여 precision | 0.8059 ± 0.0322 | 0.7922 ± 0.0227 | −1.38%p | 0.286 | 3.49%p |
| 저참여 F1 | 0.7288 ± 0.0151 | 0.7434 ± 0.0075 | +1.46%p | 0.017 | 1.50%p |
| 3연속 검출률 | 0.2997 ± 0.0505 | 0.3456 ± 0.0334 | +4.59%p | 0.029 | 5.36%p |
| 90분당 오탐 | 0.113 ± 0.094 | 0.150 ± 0.087 | +3.73%p | 0.368 | 11.33%p |

**채택 게이트 셋 모두 충족합니다** — 저참여 recall +3.39%p(기준 +3.0%p), 90분당 오탐
0.150회(기준 0.5회 이하), accuracy 하락 −0.14%p(기준 1.0%p 이하, 실제로는 상승).

**근거는 recall 숫자 단독이 아닙니다.** 세 가지가 함께 받칩니다.

1. **두 분할이 일치합니다.** 저참여 recall이 Validation +3.35%p → Test +3.39%p입니다. E0-L은
   Validation macro-F1 +2.00%p가 Test에서 +0.11%p로 사라졌고, 그것이 기각 근거였습니다.
   `alpha`를 Validation에서 고른 것이 과적합으로 이어지지 않았다는 직접 증거입니다.
2. **macro-F1이 검출한계를 넘겼습니다.** +1.26%p / 한계 1.02%p / p=0.003으로, 판단 기준 1%p와
   검출한계를 동시에 넘긴 유일한 지표입니다. 이 계열에서 처음입니다.
3. **오분류 총계가 줄었습니다** — 576.2 → 573.4/seed. 저참여 174건을 더 잡고 오탐 113건을 더
   내면서도 총계가 준 것이므로, 검출을 정확도와 맞바꾼 것이 아닙니다.

#### 저참여 recall이 검출한계 아래인 것을 어떻게 읽는가

+3.39%p < 한계 3.86%p인데 p=0.027입니다. 모순이 아니고 **한계 공식의 등분산 가정이 여기서
깨진 것**입니다. 한계는 합동 sd(0.0375·0.0220 → 약 0.031)로 계산하지만 Welch는 군별 sd를
그대로 씁니다. E0-M의 sd가 41% 작아(백본 동결의 알려진 효과 — E0-L 절 참고) Welch가 한계
공식보다 민감해집니다.

그래도 **사후 p<0.05는 사전 검정력보다 약한 증거입니다.** 그래서 이 절은 recall을 "확인됨"이
아니라 **"게이트를 넘겼고 두 분할에서 같은 크기로 재현됨"** 으로 기록하고, 채택 근거의 무게는
위 1·2·3에 둡니다. recall만 단독으로 인용하지 마십시오.

#### 무엇이 움직였는가

다수 클래스에서 중간 등급으로 질량이 옮겨갔습니다. Highly-Engaged 정답 10023 → 9852(−171)이
Barely-Engaged 617 → **724**(+107), Engaged 1711 → **1806**(+95)로 갔습니다.

E0-C\~E0-F가 가중치·샘플러로 움직이지 못했던 "다수 클래스 쪽으로 밀린 경계"를 E0-L은
움직였지만 옮긴 질량이 인접 오류로 남아 오분류가 583.8 → 603.0/seed로 **늘었습니다.**
E0-M은 같은 질량을 **정답으로 만들었습니다.** 같은 방향의 개입이 성공과 실패로 갈린 지점이
여기이고, 차이는 정확도 예산이 붙은 선택 규칙입니다.

#### `alpha`는 대부분 코너에 앉았습니다

seed별로 고른 값입니다(`epsilon`은 전부 1e-6, `constraints_satisfied`는 전부 true — 폴백
경로는 한 번도 타지 않았습니다).

| seed | alpha | T_softmax | T_ordinal | Validation 저참여 recall (기준점 → 선택) |
| --- | --- | --- | --- | --- |
| 42 | 0.0 | 1.0 | 1.0 | 0.5488 → 0.6585 |
| 43 | 0.0 | 1.0 | 1.5 | 0.5122 → 0.5793 |
| 44 | **1.0** | 1.0 | 1.0 | 0.7195 → 0.7195 |
| 45 | 0.25 | 1.5 | 2.0 | 0.5610 → 0.5854 |
| 46 | **1.0** | 1.0 | 1.0 | 0.6463 → 0.6463 |
| 47 | 0.0 | 1.0 | 1.0 | 0.5854 → 0.6159 |
| 48 | 0.5 | 2.0 | 1.5 | 0.5732 → 0.5915 |
| 49 | 0.0 | 1.0 | 1.0 | 0.5732 → 0.6037 |
| 50 | 0.0 | 1.0 | 1.0 | 0.5183 → 0.5549 |
| 51 | 0.5 | 1.0 | 1.0 | 0.6220 → 0.6402 |

**열 개 중 일곱이 코너입니다** — 순서형 단독(`alpha` 0) 다섯, 기준선 그대로(`alpha` 1) 둘.
실제로 두 head를 섞은 것은 세 개(45·48·51)뿐입니다.

그래서 이 실험이 확인한 것은 "섞으면 좋다"가 아닙니다. **"저참여 검출로 목표를 바꾸면 순서형
head가 이기고, 정확도 예산이 걸리는 seed에서는 기준선으로 자동 후퇴한다"** 입니다. 44·46은
비-코너 점이 전부 정확도 예산을 넘겨 기준점이 최선으로 남은 경우이고(선택 recall = 기준점
recall), 선택 규칙이 의도대로 거부한 결과입니다.

seed 42의 이득이 +11.0%p로 혼자 Validation 전체 이득의 3분의 1을 만듭니다. 이득은 균등하지
않습니다.

#### 남은 것

- **어느 seed를 배포할지는 이 일감이 정하지 않습니다.** `alpha`가 seed마다 다르므로 선택이
  결과를 바꿉니다. Test로 고르면 부정이므로 Validation 기준으로 정해야 하고, 5-seed 런타임
  앙상블은 범위에서 제외돼 있습니다.
- **일반화 주장은 갱신해야 합니다.** E0-10의 Test는 289를 쓸 때 이미 본 값이므로 이 Test는
  프로토콜 계열 수준에서 완전한 미공개 분할이 아닙니다. 새 holdout 또는 실제 수업 shadow
  로그로 확인하기 전까지 "채택"은 이 데이터셋 위에서의 채택입니다.
- **브라우저 지연은 아직 연산량 논거뿐입니다.** 위 추론 예산 절의 절차로 `bench.html` 실측이
  필요합니다.
- **혼합 기구를 유지할 가치가 있는지 재검토할 근거가 생겼습니다.** 세 seed만 실제로 섞었으므로,
  "순서형 head + 정확도 가드 폴백"으로 단순화해도 거의 같은 결과가 나올 수 있습니다.

#### E0-L 절의 결론을 갱신합니다

E0-L 절은 "순서 구조 축은 닫힌 것으로 봅니다"로 끝납니다. **그 판단은 이제 유효하지 않습니다.**
닫혀 있던 것은 순서 구조가 아니라 판정 기준이었습니다 — 4-class 정확도를 목표로 두면 순서형
분해는 지고, 저참여 검출을 목표로 두면 이깁니다. E0-L 절의 문장은 그 시점의 5-seed 근거로
읽고, 이 절을 최신 판단으로 봅니다.

### E0-N 시간 창 증강 — 기각

정규화 축의 첫 검정이자 마지막 검정입니다. `S15P11A105-298`. 결론부터: 증강이 과적합에
닿지 못했고, **E0-M이 최종 모델로 남습니다.** 이유는 이 절 끝의 "결과 — 기각"에 있습니다.

#### 왜 이 축인가

E0-10 10개 seed가 전부 같은 모양으로 실패합니다. Validation macro-F1이 `best_epoch`
2\~11에서 정점을 찍고 그 뒤 4\~7%p 떨어집니다(seed 42: 정점 0.5782 → 마지막 5 epoch 평균
0.5038). 정점 뒤 평탄이면 라벨 노이즈 천장이고 계속 오르는 중이면 과소적합인데, **떨어지므로
과적합**입니다.

그런데 이 계열은 정규화를 한 번도 시도한 적이 없습니다. `Adam`에 `weight_decay`가 설정된
적이 없어 0이고, 정규화는 `dropout=0.3` 하나이며, 증강은 구현 자체가 없었습니다. 반면 손실
가중치(E0-C\~E0-F), 학습 일정(E0-G·E0-K), 라벨 노이즈(E0-H·E0-I)는 모두 실패했습니다.

`weight_decay`를 먼저 쓰지 않는 이유는 과적합이 3 epoch에 시작하기 때문입니다. 감쇠항이
작동할 epoch이 없습니다. 입력 측 증강은 첫 배치부터 작동합니다.

#### 문헌이 정한 것

Iwana & Uchida, *An empirical survey of data augmentation for time series classification
with neural networks* (PLOS ONE 2021, arXiv:2007.15951). 128개 데이터셋 · 12개 방법 ·
6개 신경망.

| 결정 | 값 | 근거 |
| --- | --- | --- |
| 방법 | window warping · window slicing **둘만** | 서베이에서 warping이 VGG·ResNet·LSTM 평균 순위 1위, slicing이 그다음 |
| 쓰지 않는 것 | rotation · permutation · time warping | 같은 서베이에서 정확도를 **떨어뜨리는** 것으로 측정됨. 도메인에도 어긋납니다 — 98채널에 시선 각도 · 머리 오일러각 · \[0,1\] 블렌드셰이프가 섞여 있어 축 섞기가 의미를 깨고, 20개 세그먼트의 시간 순서는 Transformer가 존재하는 이유입니다 |
| 합성 | **금지** — 샘플당 최대 하나 | 서베이는 모델당 방법 하나만 쓰고 결합을 "미탐구"로 남깁니다. 결합을 실제로 다룬 유일한 후속(Oba·Matsuo·Iwana, ICPR 2022, arXiv:2111.03253)에서 **균등 혼합은 12개 데이터셋 중 1개에서만 최고**였고, 학습된 게이팅 네트워크가 있어야 이득이 났습니다 |
| 적용 확률 | **0.8** | 서베이 프로토콜은 원본을 유지한 채 증강본 4벌을 덧붙입니다(학습셋 5배, 원본 20%). 0.8은 그 1:4 비율을 그대로 옮긴 값입니다 |
| `window_ratio` | **0.25** (참조 기본값 0.1이 아님) | UCR 시계열은 보통 수백 스텝이지만 우리는 20입니다. 0.1은 2스텝이라 사실상 무동작입니다 |
| `scales` | \[0.5, 2.0\] | 참조 구현 그대로 |
| `reduce_ratio` | 0.9 | 참조 구현 그대로 |

#### 왜 정적 확장이 아니라 on-the-fly인가

서베이의 참조 구현(`uchidalab/time_series_augmentation`)은 학습 **전에** 데이터셋을 5배로
불려 디스크에 고정합니다. 우리는 에폭마다 샘플별로 새로 뽑습니다. 세 가지 이유입니다.

- **비용**: 정적 5배는 에폭 시간도 5배입니다. 10-seed 런에서 감당할 값이 아닙니다.
- **다양성**: 이 계열은 조기 종료로 30 epoch 근처에서 끝납니다. 정적 확장은 고정된 28,000벌을
  보여주지만, on-the-fly는 매 에폭 새로 뽑으므로 그 몇 배의 서로 다른 변형을 보여줍니다.
- 작은 학습셋에서 on-the-fly가 정적 확장보다 낫다는 것이 일관된 보고입니다. 우리 Train은
  약 7,000 클립입니다.

#### 재보간을 클램프하지 않습니다

두 방법 모두 변환 뒤 토큰 수를 20으로 되돌립니다(`position_embedding`이 20에 고정). 보간은
전 구간 **선형**이고, 선형 보간값은 두 표본의 볼록결합이므로 **입력 범위를 벗어날 수
없습니다.** 따라서 \[0,1\] 블렌드셰이프 채널에 클램프가 필요 없고, 유한성도 자동으로
보존되어 `CachedFeatureDataset`의 계약을 깨지 않습니다. `test_augmentation.py`가 이 성질을
직접 검사하므로, 누군가 나중에 비선형 보간으로 바꾸면 그 테스트가 먼저 깨집니다.

참조 구현과 한 군데 다릅니다. window slicing이 잘라낸 18스텝을 `[0, 18]`이 아니라
`[0, 17]`에 걸쳐 늘립니다. 참조는 마지막 표본 너머를 요청해 `np.interp`가 값을 고정하는데,
300스텝에서는 꼬리 한 스텝이지만 20스텝에서는 모든 증강 클립에 눈에 보이는 평탄부가 생깁니다.

#### 증강은 학습 분할에만 적용됩니다

`_load_feature_datasets`가 `train_augmentation`을 Train 데이터셋에만 넘깁니다. Validation과
Test는 손대지 않습니다 — 평가 분포가 바뀌면 다른 프로토콜과의 비교가 전부 무효가 됩니다.
정규화 통계(`compute_feature_statistics`)도 `token_arrays()`로 디스크에서 직접 읽으므로
원본 기준입니다.

재현성은 데이터셋이 소유한 전용 `np.random.default_rng(seed)`가 보장합니다. `num_workers=0`
이고 방문 순서는 loader의 seed 고정 generator가 정하므로, 같은 seed의 재실행은 같은 draw를
같은 순서로 재생합니다. 전역 numpy 스트림을 쓰지 않으므로 다른 코드가 이 순서를 밀 수
없습니다.

#### 손실을 기록합니다

`validation_history`의 각 epoch이 `train_loss`와 `validation_loss`를 함께 담습니다. 이
필드는 `configuration`에 들어가지 않으므로 **완료된 실행의 정체성을 무효화하지 않습니다.**
학습 손실은 가중치가 움직이는 **중의** 표본 가중 평균입니다(모든 학습 곡선이 그렇습니다).
E0-I 커리큘럼의 mixed 단계만 `validation_loss`가 `null`인데, 그 단계는 학습 분할에만
존재하는 평활 타깃을 맞추므로 같은 objective로 Validation을 채점하면 다른 것을 재게 됩니다.
"해당 없음"과 "0"을 구분하려고 `null`로 둡니다.

#### 실행

```bash
uv run --extra train --extra vision python -m zani_ai engagement reproduce-e0n \
  --features data/processed/engagenet \
  --output artifacts/engagement/e0n

uv run --extra train --extra vision python -m zani_ai engagement finalize-e0n \
  --features data/processed/engagenet \
  --output artifacts/engagement/e0n

uv run --extra train python ai/scripts/compare_protocols.py \
  --baseline artifacts/engagement/e0-10 \
  --variant  artifacts/engagement/e0n
```

E0-10과 **같은 특징 manifest**를 씁니다. 특징 재추출도 스키마 변경도 없습니다.

#### 결과를 읽을 때 주의할 것

`mean`/`std` 요약 위에서 시간을 휘므로, 보고되는 `std`는 새 시간 축의 실제 변동성과
어긋납니다. 세그먼트 요약 통계를 다시 계산하지 않고 그 통계열 자체를 재보간하기 때문입니다.
이 근사는 채택·기각 판단에 쓰는 seed 간 산포와는 다른 것이며, 두 숫자를 섞어 읽으면 안 됩니다.

**이 주의사항이 실은 기각 사유였습니다.** 아래 참고.

#### 결과 — 기각

jupyter04 / L40S에서 10-seed를 완주했습니다. E0-10과 특징 manifest·실행 환경이 같습니다.

Test (n=10+10):

| 지표 | E0-10 | E0-N | 차이 | Welch t | p | 검출한계 |
| --- | --- | --- | --- | --- | --- | --- |
| macro-F1 | 0.5866 ± 0.0097 | 0.5855 ± 0.0101 | −0.11%p | −0.24 | 0.810 | 1.24%p |
| QWK | 0.7796 ± 0.0135 | 0.7747 ± 0.0170 | −0.49%p | −0.72 | 0.483 | 1.92%p |
| within-1 | 0.9384 ± 0.0061 | 0.9383 ± 0.0067 | −0.01%p | −0.03 | 0.973 | 0.80%p |
| accuracy | 0.7136 ± 0.0083 | 0.7022 ± 0.0186 | −1.14%p | −1.77 | 0.101 | 1.80%p |
| 저참여 recall | 0.6673 ± 0.0375 | 0.6782 ± 0.0451 | +1.09%p | +0.59 | 0.565 | 5.20%p |
| 저참여 FPR | 0.0560 ± 0.0145 | 0.0664 ± 0.0151 | +1.03%p | +1.56 | 0.135 | 1.85%p |
| 저참여 precision | 0.8059 ± 0.0322 | 0.7805 ± 0.0295 | −2.54%p | −1.84 | 0.083 | 3.87%p |

**얻은 것이 없습니다.** 모든 차이가 검출한계 아래이고 부호도 이득 쪽이 아닙니다. accuracy만
채택 게이트(1.0%p 이하)를 넘지만 그 하락(1.14%p)조차 자기 검출한계(1.80%p) 아래이므로,
기각 근거는 가드 실패가 아니라 **이득의 부재**입니다. 오분류는 576.2 → 599.1/seed로
늘었습니다.

##### 과적합 자체가 줄지 않았습니다

이 프로토콜이 존재한 이유는 Test 지표가 아니라 정점 후 하락이었으므로, `validation_history`로
직접 확인했습니다.

| 지표 | E0-10 | E0-N | 차이 | Welch t | 검출한계 |
| --- | --- | --- | --- | --- | --- |
| 정점 후 하락(정점 − 마지막 5 epoch 평균) | 0.0545 ± 0.0137 | 0.0511 ± 0.0112 | −0.0034 | −0.61 | 0.0156 |
| 정점 macro-F1 | 0.5745 ± 0.0108 | 0.5757 ± 0.0076 | +0.0012 | +0.29 | 0.0117 |
| `best_epoch` | 4.1 ± 3.0 | 3.7 ± 2.2 | −0.4 | −0.34 | 3.3 |

하락이 6% 줄었지만 검출한계의 5분의 1 크기입니다. 그리고 **정점이 늦춰지지 않았습니다** —
오히려 당겨졌습니다. 증강이 과적합의 시작도 속도도 바꾸지 못했습니다.

##### 학습 손실이 이유를 말합니다

마지막 epoch 기준(10 seed 평균):

| | 값 |
| --- | --- |
| 학습 손실 | 0.2335 ± 0.0219 |
| 검증 손실 | 1.6434 ± 0.1881 |
| gap | 1.4098 |
| 균등분포 CE (`ln 4`) | 1.3863 |

**학습 손실 0.23은 증강된 데이터를 그대로 암기했다는 뜻입니다.** 증강이 정규화로 작동했다면
매 epoch 타깃이 움직이므로 학습 손실이 높은 채 머물러야 합니다. 그러지 않았습니다.

검증 손실은 10 seed 중 8개가 균등분포 CE보다 나쁩니다. 클래스 빈도만 출력하는 모델이 1.175인데
정확도 70%짜리 모델이 1.64를 냅니다 — 틀릴 때 확신에 차서 틀립니다. 다만 이 손실은
**마지막 epoch**의 것이고 배포되는 체크포인트는 `best_epoch`(1\~7)의 것이므로, 버려지는
가중치의 상태입니다. E0-10에는 손실 기록이 없어 "증강이 이것을 악화시켰다"고는 말할 수
없습니다.

##### 왜 문헌 1위 방법이 우리에게서 안 통했는가

**토큰 20개가 원신호가 아니라 세그먼트 요약 통계이기 때문입니다.** 서베이의 128개 UCR
데이터셋은 수백 스텝짜리 원시 시계열이고, 거기서 시간을 휘면 국소 파형이 실제로 바뀝니다.
우리 토큰은 각 세그먼트의 `mean`/`std`를 이미 뽑아 놓은 것이라, 그 열을 재보간해도 평균된
값들 사이를 선형으로 오갈 뿐입니다. 위 "결과를 읽을 때 주의할 것"에 해석상 주의사항으로
적어 둔 근사가, 실은 방법이 실패한 원인이었습니다.

혼동 행렬은 증강이 무동작은 아니었음을 보여줍니다 — Barely-Engaged 정답률 27.67% →
**31.35%**(+3.68%p), Engaged 42.78% → 43.95%, 대신 Highly-Engaged 91.28% →
88.58%(−2.70%p). 다수 클래스에서 중간으로 질량이 옮겨갔고, macro-F1이 평평한 것은 그 이득과
손실이 상쇄됐기 때문입니다. 하지만 저참여 recall과 FPR이 함께 오르고 precision이 떨어진 것은
**판별력 개선이 아니라 프런티어 위의 이동**입니다. recall 1점당 오탐 1점(E0-M은 4.5점당
1점)이라는 교환비가 그것을 말합니다.

##### 남기는 것

기각이지만 코드는 남습니다. 세 가지가 이후 일감에 쓰입니다.

- `augmentation.py`와 `ExperimentSpec.augmentation` — 세그먼트 마스킹처럼 토큰 축에 직접
  작용하는 증강을 추가할 자리입니다.
- `validation_history`의 `train_loss` / `validation_loss` — 이후 모든 프로토콜이 과적합을
  사후 확인할 수 있습니다. E0-10 이전 실행에는 이 필드가 없습니다.
- 이 진단 — 다음 후보의 순서를 바꿉니다. **증강 강도를 올리는 것은 답이 아닙니다.**
  프레임 게이트 완화 + zero placeholder(가짜 표본 대신 Not-Engaged 실제 표본 27% 회수),
  세그먼트 마스킹, 모델 용량 축소 순입니다. 학습 CE 0.23은 마지막 항목에 처음으로 직접
  근거를 붙입니다.

##### E0-M을 최종 모델로 유지합니다

정규화 축이 열렸다가 닫혔으므로, **`S15P11A105-289`의 E0-M이 이 계열의 최종 채택 모델로
남습니다.** E0-N을 E0-M의 stage 1로 넣는 선택지는 취하지 않습니다 — E0-N의 저참여 recall
이득은 프런티어 위의 이동이고, E0-M의 `alpha`·온도 그리드가 Validation에서 정확도 예산까지
걸어 같은 이동을 이미 무료로 수행합니다. 더 나쁜 출발점과 함께 손잡이를 하나 더 주는 셈입니다.

E0-M의 stage 2에 증강을 거는 선택지도 취하지 않습니다. 동결 백본 뒤의 순서형 head는 약 99k
파라미터인데, 인코더가 실제로 학습하는 E0-N에서도 막지 못한 암기를 그보다 약해진 교란으로
막을 수 없습니다. E0-M은 이미 E0-10보다 seed 간 산포가 작습니다(Test macro-F1 sd 0.0062 대
0.0097).

### E1-P 문헌 정합 재구현 — 기각

`reproduce-e1p`는 arXiv:2403.17175의 non-ordinal ST-GCN을 문헌 기준으로 다시 만든
프로토콜입니다. 그래프를 단일 `A+I`(K=1)로, 공간 projection을 shared `W_spatial`로,
edge weight를 layer별 learnable `M`으로 바꾸고, canonical 구현의 block 순서·input
`BatchNorm1d(C*V)`·`Conv2d(256,4,1×1)` head를 적용했습니다. 입력은 30fps 300스텝이고
결측 프레임은 forward-fill 없이 0으로 둡니다. 학습은 Adam / batch 16 / lr 1e-3 /
300 epoch / 100·200에서 ×0.1입니다.

**결론부터: 문헌 재현에 실패했고, 제품 후보로도 기각합니다.** 아래가 근거입니다.

#### 파라미터 수는 문헌과 대조됐습니다

| 항목 | 개수 |
| --- | --- |
| 이 모델 (4-class) | 879,844 |
| learnable `M` 3개 (3 × 78 × 78) | −18,252 |
| edge importance 제외 | **861,592** |
| 논문 보고값 | **861,688** |
| 잔차 | 96 (0.011%) |

논문은 수식에 `M`을 넣었지만 **보고한 파라미터 수에는 넣지 않았습니다.** 포함하면 2.1%
벗어납니다. 독립적인 근거가 둘 있습니다. 논문의 ordinal 861,431과 non-ordinal 861,688의
차이 257이 `Conv2d(256,4,1)`과 `Conv2d(256,3,1)`의 차이 257과 정확히 일치하므로 head
형태가 확인되고 그 앞단이 두 변형에서 공유됩니다. 그리고 공개된 세부의 어떤 구조적
해석도 `A+I` 비영 원소 크기의 layer별 `M`으로는 861,688에 닿지 않습니다 — 가장 가까운
해가 layer당 442개를 요구하는데 78노드 얼굴 삼각분할은 약 494개(hull 23점)를 만들고,
442는 hull이 49점이어야 하는 값입니다.

남은 96개는 논문 미공개 세부에 있습니다. **목표에 맞춰 플래그를 역으로 맞추지
않았습니다** — input BN 축과 bias 조합을 흔들면 −6까지 붙일 수 있지만, 맞추려고 만든
수치는 일치의 증거가 아닙니다.

#### 표본 수는 문헌과 일치시켰습니다

커버리지 게이트(구간당 유효 프레임 3장)가 논문이 학습에 쓴 클립을 떨어뜨리고 있었습니다.
논문 §5는 "samples with occluded or absent faces, i.e., no facial landmarks"를
Not-Engaged로 분류한다고 보고하므로 그 표본을 버리지 않았고, 그 클립은 대부분
Not-Engaged입니다. `--keep-low-coverage`로 게이트를 끈 결과:

| | 이전 E1 | E1-P | 문헌 |
| --- | --- | --- | --- |
| Train 전체 | 7,235 | 7,879 | 7,983 |
| Train Not-Engaged | 1,054 | 1,446 | 1,550 |
| Validation 전체 | 980 | **1,071** | 1,071 |

**Validation은 4개 클래스가 전부 정확히 일치합니다**(132 / 97 / 273 / 569). Train에 남은
−104는 전부 Not-Engaged 한 클래스이고, 우리 EngageNet 사본에 없는 파일입니다 — 논문
전체가 11,311개인데 contract가 훑은 것이 11,206개입니다.

#### 학습 일정은 최고점을 올리지 못했습니다

seed 42가 300 epoch을 완주하고 학습률이 `1e-3 → 1e-4 → 1e-5`로 epoch 100·200에서 정확히
꺾인 것을 `validation_history`가 기록합니다.

```
epoch   0- 99   평균 0.6379   최고 0.6676   ← 최고점 (epoch 69)
epoch 100-199   평균 0.6468   최고 0.6564
epoch 200-299   평균 0.6480   최고 0.6583
```

감쇠는 평균을 올리고 진동을 줄였지만 **감쇠 전 최고점을 한 번도 넘지 못했습니다.**
E0-K의 "300 epoch 중 296이 낭비"와 같은 계열이지만 결이 다릅니다 — 여기서 감쇠가 사는
값은 최고 정확도가 아니라 최종 모델의 안정성입니다.

#### 기각 근거: E0가 세 축에서 모두 낫습니다

| 프로토콜 | Validation accuracy | macro-F1 | 논문(0.6937) 대비 |
| --- | --- | --- | --- |
| **E0-10** (Transformer, 10 seed) | **0.6716 ± 0.0132** | 0.5745 ± 0.0109 | **−2.21%p** |
| **E0-L** (순서형 K-1, 5 seed) | 0.6688 ± 0.0171 | **0.5944 ± 0.0162** | −2.49%p |
| E1-P (seed 42, macro-F1 선택) | 0.6527 | 0.5449 | −4.10%p |
| E1-P (seed 42, best-accuracy) | 0.6676 | — | −2.61%p |
| E1-A (5 seed) | 0.6412 ± 0.0121 | ~0.53 | −5.25%p |
| E1 (5 seed) | 0.6088 ± 0.0318 | ~0.50 | −8.49%p |

95% CI는 E0-10 `[0.6634, 0.6798]`, E0-L `[0.6538, 0.6838]`입니다.

**이미 배포 중인 Transformer가 우리 ST-GCN 재구현보다 문헌 수치에 더 가깝습니다.** E1-P의
단일 seed 0.6527은 E0-10의 95% CI 아래쪽(0.6634)에도 못 미치고, 가장 유리하게 읽은
best-accuracy 0.6676조차 E0-10 평균보다 낮습니다.

macro-F1은 격차가 더 큽니다(E0-L 0.5944 → E0-10 0.5745 → E1-P 0.5449). confusion matrix가
이유를 말해줍니다. 행이 실제, 열이 예측입니다.

```
Not-Engaged      91  19  13   9   재현율 68.9%
Barely-Engaged   12  24  38  23   재현율 24.7%
Engaged           9  22  88 154   재현율 32.2%   <- 154개가 Highly 로
Highly-Engaged    4  10  59 496   재현율 87.2%
```

가운데 두 클래스가 무너져 다수 클래스로 흘러가므로 accuracy를 벌고 macro-F1을 잃습니다.
Highly 예측이 682개인데 실제는 569개입니다. 한편 **Not-Engaged 재현율 68.9%는 게이트를
끈 결정을 뒷받침합니다** — 논문 §5가 말한 그 클래스이고 496건을 되살린 자리입니다.

여기에 지연이 겹칩니다. E0는 WebGPU 5.4ms / WASM 9.5ms, E1은 84ms / 1,080ms이며 300스텝은
그 3배입니다. **정확도·클래스 균형·속도 세 축에서 모두 E0가 낫습니다.**

#### 남은 미공개 변수

격차가 1%p를 넘으므로 재현 성공으로 표시하지 않습니다. 단일 seed로는 −2.61%p가 실재하는지도
확정되지 않습니다 — 이 계열 seed 표준편차가 0.010~0.012이므로 약 2.4σ이고, 성공 판정 폭이
한 seed의 노이즈보다 좁습니다. seed를 더 쌓지 않은 이유는 E0-10과의 1.9%p 열세가 seed로
뒤집히는 종류가 아니기 때문입니다.

남은 변수를 측정된 기여도 순으로 남깁니다.

| 변수 | 관측된 기여 | 비고 |
| --- | --- | --- |
| checkpoint 선택 규칙 | **1.49%p** | epoch 69(0.6676) vs epoch 95(0.6527). 논문은 accuracy를 보고하면서 선택 규칙을 밝히지 않음. 재학습 불필요 |
| 68개 index 대응 | 미측정 | 논문이 exact index를 공개하지 않아 그래프 노드가 다름 |
| input BN 축 | 미측정 | 논문은 "input batch normalization"만 명시 |

**논문의 중심 주장 하나가 우리 손에서 재현되지 않았습니다.** 논문은 ST-GCN이 Transformer
기반 SOTA를 이겼다고 보고하는데 우리 데이터에서는 Transformer가 우리 ST-GCN을 이깁니다.
다만 이것을 "논문이 틀렸다"로 읽지 않습니다 — 우리 ST-GCN이 논문보다 4.1%p 낮으므로,
순서가 뒤집힌 원인은 위 미공개 변수 쪽일 가능성이 더 큽니다.

#### 남는 값

기각하지만 이 작업이 남긴 것은 유효합니다. 커버리지 게이트가 Not-Engaged 496건을 조용히
버리고 있었다는 사실은 **E1 계열 전체에 걸려 있던 체계적 편향**이고, Validation split이
이제 문헌과 정확히 일치합니다. `E1`/`E1-A`/`E1-B`의 프로토콜·스키마·체크포인트는 손대지
않았으므로 그 결과는 같은 identity로 계속 조회·재현됩니다.

### seed 수와 검출력

지금까지의 프로토콜 비교는 **원리적으로 구별할 수 없는 차이를 놓고 순위를 매겨** 왔습니다.
이 절은 그 해상도를 정의하고, 후보 프로토콜의 기본 seed 수를 10으로 올린 근거와 그 변경이
기존 산출물에 미치는 영향을 기록합니다.

#### 무엇이 문제였는가

Validation macro-F1의 seed 표준편차는 이 계열에서 0.010\~0.012입니다. 그런데 E1을 제외한
프로토콜 아홉 개의 Validation macro-F1은 0.5670\~0.5825, 폭 **1.55%p** 안에 모두 들어
있습니다. 5-seed 비교가 구별할 수 있는 최소 차이가 약 2%p이므로, 이 아홉 개의 순위는
측정이 만들어낸 것이고 프로토콜이 만들어낸 것이 아닙니다.

#### 검출 가능한 최소 차이

두 표본 비교에서 검정력 80%, 양측 α = 0.05로 검출할 수 있는 최소 차이(MDE)는

```
MDE = (z_0.975 + z_0.80) × sd × √(1/n₁ + 1/n₂) ≈ 2.8016 × sd × √(1/n₁ + 1/n₂)
```

입니다. macro-F1의 sd = 0.011을 넣으면 (지표마다 sd가 다르므로 MDE도 다릅니다 —
accuracy는 sd가 더 커서 아래 실측 절의 값이 더 큽니다):

| seed (기준선 + 후보) | MDE | 판단 |
| --- | --- | --- |
| 5 + 5 | 1.95%p | 지금까지의 상태. 아홉 개 프로토콜 전체 폭보다 넓다 |
| 10 + 5 | 1.69%p | 후보만 올린 경우. 거의 개선되지 않는다 |
| 8 + 8 | 1.54%p | |
| **10 + 10** | **1.38%p** | 채택 |
| 23 + 23 | 0.91%p | 1%p를 보려면 필요한 수. 이 계열의 GPU 예산 밖 |

정규 근사(비중심 t가 아님)이며 요구 seed 수를 근소하게 과소평가합니다. 과소평가 방향은
"필요 seed 수를 더 크게 부르지 않는" 쪽이므로, 보수적으로 읽으려면 표의 MDE를 하한으로
봅니다.

**후보만 올려서는 의미가 없습니다.** 10 + 5는 1.69%p로 5 + 5의 1.95%p에서 거의 움직이지
않습니다. MDE가 `√(1/n₁ + 1/n₂)`에 비례하므로 기준선이 5에 묶여 있으면 후보 쪽 seed를
아무리 늘려도 `√(1/5)`가 남기 때문입니다. 그래서 기준선도 함께 올립니다.

#### 적용 범위

- `CANDIDATE_SEEDS = (42, …, 51)`가 `ExperimentSpec.seeds`의 **기본값**입니다. 앞으로
  추가되는 프로토콜은 별도 조치 없이 10-seed로 측정됩니다.
- E0\~E0-L, E1\~E1-B **열여섯 개는 `seeds=E0_SEEDS`로 명시 고정**했습니다. 아래 identity
  영향 때문이며, 이들을 10-seed로 재실행하지 않는다는 결정이기도 합니다.
- **E0-10**을 추가했습니다. E0와 schema·모델·손실·일정이 모두 같고 seed 수만 10인 비교
  기준선입니다. E0-A\~E0-L처럼 요인을 하나 바꾼 변종이 아니므로 다음 알파벳이 아니라
  개수를 이름에 씁니다.

```powershell
uv run python -m zani_ai engagement reproduce-e0-10 --features <root> --output artifacts/engagement/e0-10 --device cuda
uv run python -m zani_ai engagement finalize-e0-10 --features <root> --output artifacts/engagement/e0-10 --device cuda
```

#### E0-10 실측 — 가정이 맞았습니다

jupyter04 / L40S에서 10-seed를 완주했습니다. E0-10은 E0와 프로토콜이 같으므로 이 비교는
후보 평가가 아니라 **정합성 점검**입니다. 차이가 0에 수렴해야 정상이고, 그렇게 나왔습니다.

```
지표      E0(5-seed)       E0-10(10-seed)   차이               Welch t  p        검출한계
accuracy  0.6665 ± 0.0165  0.6716 ± 0.0132  +0.0051 (+0.51%p)  t=+0.60  p=0.567  2.20%p
macro-F1  0.5745 ± 0.0132  0.5745 ± 0.0109  -0.0000 (-0.00%p)  t=-0.00  p=0.998  1.78%p
QWK       0.7060 ± 0.0167  0.7039 ± 0.0142  -0.0021 (-0.21%p)  t=-0.24  p=0.817  2.30%p
within-1  0.9598 ± 0.0033  0.9585 ± 0.0050  -0.0013 (-0.13%p)  t=-0.61  p=0.551  0.70%p
```

이 표의 Welch 통계량은 읽지 마십시오. 두 실행이 seed 42\~46을 **공유**하므로 독립 표본
가정이 깨집니다. 의미가 있는 것은 평균과 sd이며, 그 둘이 확인해 주는 것은 둘입니다.

**하나, 검정력 계산의 sd 가정이 맞았습니다.** 위 표에 쓴 가정은 sd = 0.011이고, 10-seed
실측 macro-F1 sd는 0.0109입니다. 실측 sd로 다시 계산한 10 + 10의 MDE는 다음과 같습니다.

| 지표 | 10-seed 실측 sd | 10 + 10 MDE |
| --- | --- | --- |
| accuracy | 0.0132 | 1.65%p |
| macro-F1 | 0.0109 | 1.37%p |
| QWK | 0.0142 | 1.78%p |
| within-1 | 0.0050 | 0.63%p |

(양쪽 sd가 같다고 본 값입니다. 후보의 sd가 다르면 합동 분산이 달라집니다 — 예를 들어
E0-L처럼 백본을 동결해 sd가 작아지는 프로토콜은 이보다 낮게 나옵니다.)

**둘, 기존 +2%p accuracy 목표는 5-seed에서 애초에 검출할 수 없는 기준이었습니다.**
accuracy sd 0.0165로 5 + 5의 MDE는 **2.92%p**입니다. E0-J·E0-K·E0-L을 판정한 "Validation
accuracy +2.0%p" 바가 그 설계의 검출한계 아래에 있었다는 뜻이고, 목표를 달성하더라도
노이즈와 구별할 수 없었다는 뜻이기도 합니다. 10 + 10에서 1.65%p로 내려가면서 이 목표는
처음으로 검정 가능해집니다.

Test도 같은 방식으로 완주했고 결론이 같습니다.

```
지표            E0(5-seed)       E0-10(10-seed)   차이               검출한계
macro-F1        0.5905 ± 0.0120  0.5866 ± 0.0097  -0.0040 (-0.40%p)  1.60%p
QWK             0.7839 ± 0.0167  0.7796 ± 0.0135  -0.0043 (-0.43%p)  2.23%p
within-1        0.9401 ± 0.0078  0.9384 ± 0.0061  -0.0017 (-0.17%p)  1.02%p
accuracy        0.7098 ± 0.0093  0.7136 ± 0.0083  +0.0038 (+0.38%p)  1.32%p
인접 오류 비중  0.7930 ± 0.0300  0.7844 ± 0.0244  -0.0086 (-0.86%p)  4.02%p
```

Test 실측 sd로 계산한 10 + 10의 MDE는 macro-F1 **1.22%p**, accuracy 1.04%p, QWK 1.69%p,
within-1 0.76%p, 인접 오류 비중 3.06%p입니다. macro-F1은 판단 기준 1%p와 1.22%p 사이가
좁으므로, Test 판정에서 1.00\~1.22%p 구간의 차이는 "기준은 넘었으나 검정력이 모자란다"에
해당합니다. 인접 오류 비중은 10-seed에서도 3%p 아래를 볼 수 없으므로 **단독 판정 근거로
쓰지 마십시오** — 방향을 보는 보조 지표입니다.

#### seed 목록은 수치에 영향을 주지 않습니다 — 실측 확인

위 identity 절의 "seed *n*의 학습은 목록 길이와 무관하다"는 서술을 직접 확인했습니다.
E0(5-seed)와 E0-10(10-seed)이 공유하는 seed 42\~46의 Validation macro-F1은 배정밀도
전 자리가 일치합니다.

| seed | e0-clean | e0-10 |
| --- | --- | --- |
| 42 | 0.5782436186487676 | 0.5782436186487676 |
| 43 | 0.5572929893373426 | 0.5572929893373426 |
| 44 | 0.5684745830283126 | 0.5684745830283126 |
| 45 | 0.5931183541089463 | 0.5931183541089463 |
| 46 | 0.5752392406580340 | 0.5752392406580340 |

즉 재사용을 막는 것은 수치가 아니라 identity 규약입니다. 규약을 완화하면 seed를 이어 붙일
수 있다는 뜻이지만, 그 완화는 여전히 택하지 않습니다 — 완료된 실행의 기록된 identity를
사후에 고쳐 쓰는 경로가 생기고, 그 위험이 E0 재측정 비용보다 큽니다.

#### pooled confusion matrix는 seed 수에 비례합니다

Test 비교의 `오분류 총계`와 pooled confusion matrix는 seed에 걸쳐 **합산**한 값이라 seed
수가 다르면 그대로 비교할 수 없습니다. E0 2,919와 E0-10 5,762는 품질 차이가 아니라 5-seed와
10-seed의 차이입니다(583.8/seed → 576.2/seed). 출력이 seed 수와 seed당 값을 함께 찍으므로
합산값만 읽지 마십시오.

#### 재현성 identity에 미치는 영향

`seeds`는 `_build_configuration`에 들어가므로 **seed 목록을 바꾸면
`configuration_sha256`이 바뀝니다.** 결과는 셋입니다.

1. 기존 열여섯 개 프로토콜의 해시는 그대로입니다. 기본값을 바꾸면서 전부 명시 고정했고,
   `tests/engagement/test_experiment_identity.py`의 고정 해시 표가 이를 잠급니다.
2. **E0의 완료된 5개 seed는 E0-10에서 재사용되지 않습니다.** `_validate_summary_identity`가
   기록된 `configuration`과 현재 `configuration`을 통째로 비교하므로, `artifacts/engagement/e0`를
   E0-10의 `--output`으로 주면 "different configuration"으로 거부합니다. E0-10은 **자기
   출력 디렉터리에서 10개 seed를 처음부터** 돕니다.
3. seed 파일 자체는 원리적으로는 호환됩니다. seed *n*의 학습은 목록의 길이와 무관하므로
   같은 manifest·환경에서 seed 42의 결과는 5-seed 설정에서든 10-seed 설정에서든 같습니다.
   재사용을 막는 것은 수치가 아니라 identity 규약이며, 그 규약을 완화하는 쪽은 **택하지
   않았습니다** — E0 재측정 비용이 그 완화의 위험보다 싸기 때문입니다(아래).

#### 기존 5-seed 결과를 10-seed 결과와 직접 비교할 수 있는가

**점추정은 비교할 수 있고, 결론의 해상도는 비교 대상 중 작은 쪽이 정합니다.** 두 실행은 같은
모집단의 같은 평균을 추정하고 `compare_protocols.py`의 Welch 검정은 n이 달라도 유효하므로,
10-seed 후보를 기존 5-seed E0에 붙여 읽는 것 자체는 틀리지 않습니다. 다만 그 비교의 MDE는
1.69%p라 이 절이 없애려는 문제가 대부분 남습니다. 따라서 **채택·기각 판단은 10 + 10에서만**
내리고, 5-seed 결과는 그때까지 참고 수치로 둡니다.

#### 판단 규칙

**1%p 미만 차이로는 채택·기각 결론을 내지 않습니다.** 부호가 아무리 깨끗해도 보류입니다.
10 + 10의 MDE 1.38%p는 이 기준보다 크므로, 1%p\~1.38%p 구간의 차이는 "기준은 넘었지만
검정력이 모자란다"로 읽고 seed를 더 쌓을지 따로 판단합니다.

`compare_protocols.py`가 이 규칙을 출력에 넣습니다.

- 지표별 **`검출한계`** 열 — 실측 sd와 실제 seed 수로 계산한 MDE. 차이가 이보다 작으면
  "차이 없음"이 아니라 "이 비교로는 알 수 없음"입니다.
- Test 비교에서 macro-F1이나 QWK가 1%p 미만으로 움직이면 `구별 불가`로 표시하고
  `판정: 보류`를 냅니다. Validation 비교도 accuracy 차이가 1%p 미만이면 `판정: 보류`입니다.

#### 이 규칙을 과거 판정에 소급하면

**대부분의 기각이 보류가 됩니다.** 예를 들어 E0-L의 판정 근거였던 Validation accuracy
차이는 +0.22%p였고, 새 규칙에서 같은 명령을 다시 돌리면 `판정: 실패`가 아니라
`판정: 보류`가 나옵니다.

```
지표      E0               E0-L             차이               Welch t  p        검출한계
accuracy  0.6665 ± 0.0165  0.6688 ± 0.0171  +0.0022 (+0.22%p)  t=+0.21  p=0.838  2.98%p
macro-F1  0.5745 ± 0.0132  0.5944 ± 0.0162  +0.0200 (+2.00%p)  t=+2.14  p=0.067  2.62%p
```

이 일감은 **과거 판정을 다시 내리지 않습니다.** 아래 GPU 비용 절의 이유로 기각된
프로토콜을 10-seed로 재측정하지 않기로 했고, 재측정 없이 판정만 "보류"로 바꾸면 기록만
흔들리고 답은 나오지 않기 때문입니다. 각 프로토콜 절의 판정은 **그 시점의 5-seed 근거로
내려진 것**으로 읽어야 하며, 그 근거가 1%p 미만이면 지금 기준으로는 결론이 아니라 미결입니다.

실질적인 결과는 **우승 모델이 아직 정해지지 않았다**는 것입니다. E0 계열의 순위는
1.55%p 폭 안에 있고 그 폭 전체가 5-seed의 검출 한계 아래이므로, 우승 모델 선정은 E0-10
기준선과 10-seed 후보가 나온 뒤에 내려야 합니다.

#### GPU 비용

seed 수에 비례합니다. E0의 실측 `best_epoch`은 [4, 2, 3, 11, 7]이고 각 seed가 patience 20을
더 돌므로 5-seed 합계가 약 127 epoch, E0-10은 약 254 epoch입니다. 이 계열에서 가장 싼
재측정이고, 기존 프로토콜을 10-seed로 되돌리지 않는 이유이기도 합니다 — 예를 들어 E0-K는
300 × 10 = 3,000 epoch이 됩니다. 이미 기각된 프로토콜에 그만한 시간을 쓸 근거가 없습니다.

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

### 측정 결과

측정 대상은 E0-clean seed-42(`mediapipe_98_v1`)와 E1 seed-42(`landmark_78_v1`)입니다.
E0-clean은 원격 L40S에서 내보낸 파일을 가져왔고, 두 파일 모두 `record.json`의 SHA-256과
대조해 확인했습니다.

| 모델 | provider | MB | min | median | p95 | 예산 대비 (median) |
| --- | --- | --- | --- | --- | --- | --- |
| E0 Transformer | WebGPU | 12.58 | 5.4ms | 6.1ms | 9.0ms | 164배 |
| E0 Transformer | WASM | 12.58 | 8.4ms | 9.5ms | 11.2ms | 105배 |
| E1 ST-GCN | WebGPU | 3.70 | 79.0ms | 84.0ms | 87.9ms | 11.9배 |
| E1 ST-GCN | WASM | 3.70 | 990.6ms | 1,079.9ms | 5,971.6ms | **0.93배 — 초과** |

E0는 두 provider 모두 예산에 크게 못 미칩니다. 최악 조건인 WASM에서도 p95가 11.2ms로 1초의
1.1%입니다.

E1은 WebGPU에서만 예산 안에 들어옵니다. WASM은 median만으로 예산을 넘고, p95 약 6초는 창
하나를 처리하는 동안 새 창이 여섯 번 도착한다는 뜻입니다. min이 990.6ms이므로 가끔 튀는 것이
아니라 최선 조건조차 예산에 닿아 있습니다.

파일 크기와 지연은 반대로 갑니다. E1은 E0의 3분의 1 크기인데 WASM에서 114배 느립니다. 병목은
파라미터 수가 아니라 연산량입니다 — 토큰 20개짜리 Transformer와 달리 ST-GCN은 100프레임 ×
78노드에 3-파티션 그래프 컨볼루션을 64→128→256 채널로 쌓습니다. 모델을 키울 여유를 볼 때
봐야 할 축은 파라미터 용량이 아니라 시퀀스 길이 × 노드 수입니다.

### 측정 환경

| 항목 | 값 |
| --- | --- |
| 기기 | Windows 11 25H2 (Build 26200.8875) |
| 브라우저 | Chrome 150.0.7871.187 (64비트) |
| WebGPU 어댑터 | Intel gen-12lp (Iris Xe 내장) |
| onnxruntime-web | 1.27.0 |
| 표본 | warmup 5회 후 30회 |
| 측정일 | 2026-08-03 |

이 노트북에는 NVIDIA RTX 4050 Laptop GPU도 있지만 WebGPU는 내장 그래픽을 잡습니다. Windows에서
`requestAdapter()`의 `powerPreference`가 무시되기 때문이며(crbug.com/369219127),
`high-performance`를 요청해도 어댑터는 Intel gen-12lp 그대로였습니다. 위 WebGPU 수치는 내장
그래픽 기준이고, 이는 교실 노트북 대부분과 같은 조건입니다.

같은 기기에서 두 회차를 측정했고 결론은 같았습니다. 위 표는 2회차입니다.

| 모델 / provider | 1회차 median / p95 | 2회차 median / p95 |
| --- | --- | --- |
| E0 / WebGPU | 7.6 / 9.6ms | 6.1 / 9.0ms |
| E0 / WASM | 10.6 / 12.4ms | 9.5 / 11.2ms |
| E1 / WebGPU | 83.3 / 88.7ms | 84.0 / 87.9ms |
| E1 / WASM | 1,047.8 / 5,994.3ms | 1,079.9 / 5,971.6ms |

E1 WASM의 p95가 median의 약 6배로 벌어지는 것도 두 회차 모두 재현됐습니다.

### 수치를 읽을 때

- 합성 입력을 단독으로 잰 값이라 MediaPipe가 같은 1초를 나눠 쓰는 실사용 조건보다 낙관적입니다.
  실제 여유는 표의 배수보다 작습니다.
- p95는 30회 표본에서 뽑으므로 사실상 두 번째로 느린 값입니다. 드문 꼬리를 대표하지 않습니다.
- WebGPU 세션 생성은 두 모델 모두 성공했습니다. 지원되지 않는 연산자로 인한 실패는 없었습니다.

WebGPU를 못 쓰는 기기가 최악 조건이라는 전제에서, E0는 두 provider 모두 감당하며 모델을 크게
키울 여지가 있습니다. E1 ST-GCN은 WebGPU에서만 감당하고 WASM 폴백에서는 이 형태 그대로 채택할
수 없습니다.

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
