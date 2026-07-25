# EngageNet MediaPipe 재현 및 브라우저 추론 설계

## 1. 목표

2023 EngageNet 논문의 시선(Gaze), 머리 자세(Head Pose), 얼굴 Action Unit(AU) 기반
Transformer 베이스라인을 MediaPipe Face Landmarker 특징으로 적응해 재현한다. PyTorch로
학습한 모델을 ONNX로 내보내고, 웹캠 영상이 브라우저 밖으로 나가지 않는 실시간 웹 추론
페이지를 제공한다.

이 구현은 OpenFace와 MARLIN을 그대로 실행하는 논문 전체 파이프라인의 정확한 재현이
아니다. 논문에서 비교 가능한 공식 테스트 정확도가 가장 높은 Gaze + Head Pose + AU
Transformer의 시간 분할과 네트워크 구조를 유지하고, 특징 추출기만 MediaPipe로 교체하는
적응형 재현이다.

## 2. 근거와 범위

- EngageNet은 약 11,300개의 10초 영상과 4개 참여도 클래스로 구성된다.
- 공식 subject-independent 분할은 참가자 기준 Train 90명, Validation 11명, Test 26명이다.
- 논문의 Transformer는 10초 영상을 동일 길이의 20개 구간으로 나눈다.
- 각 구간의 입력 토큰은 프레임 특징의 평균과 표준편차다.
- 논문 OpenFace 입력은 Gaze 16차원, Head Pose 12차원, AU 70차원으로 총 98차원이다.
- Gaze + Head Pose + AU Transformer의 공식 테스트 정확도는 67.61%다.

근거 자료:

- 논문: <https://arxiv.org/pdf/2302.00431>
- 공식 베이스라인: <https://github.com/engagenet/engagenet_baselines>

데이터셋과 사전 학습 가중치는 저장소에 포함하지 않는다. 샘플 영상이나 합성 학습 데이터를
생성하는 기능도 제공하지 않는다.

## 3. 입력 데이터 계약

사용자는 다음 파일을 로컬 EngageNet 루트에 배치한다.

```text
<engagenet-root>/
├─ final_labels.csv
├─ train.txt
├─ valid.txt
├─ test.txt
└─ videos/
   └─ <clip-id>.mp4
```

`train.txt`, `valid.txt`, `test.txt`에는 한 줄에 하나의 clip ID가 들어간다.
`final_labels.csv`에는 clip ID와 다음 중 하나의 레이블이 있어야 한다.

1. `Not-Engaged`
2. `Barely-Engaged`
3. `Engaged`
4. `Highly-Engaged`

공식 코드에 쓰인 `Barely-engaged` 대소문자 표기도 같은 클래스로 정규화한다. 열 이름이나 영상
확장자의 사소한 차이는 명령행 옵션으로 지정할 수 있다. 분할 간 clip ID가
중복되거나, 알 수 없는 레이블이 있거나, 목록의 영상이 없으면 전처리를 시작하기 전에 실패한다.
공식 subject ID가 메타데이터에 제공되면 분할 간 subject 중복도 검증한다.

## 4. MediaPipe 특징 스키마

기본 스키마 이름은 `mediapipe_98_v1`이다. 프레임마다 다음 49개 원시 특징을 만든다.

- Gaze proxy 8개: 왼쪽과 오른쪽 iris 중심의 눈 윤곽 내 정규화 좌표 4개, 두 눈 좌표의 평균
  2개, 좌우 차이 2개. 좌우 눈의 수평 좌표는 각각 바깥 눈꼬리에서 안쪽 눈꼬리 방향으로 0~1,
  수직 좌표는 위 눈꺼풀에서 아래 눈꺼풀 방향으로 0~1이 되도록 정의한다.
- Head Pose 6개: 얼굴 변환 행렬에서 얻은 yaw, pitch, roll 3개와 nose landmark의 정규화된
  화면 x, y, inter-ocular distance의 역수로 표현한 상대 z 3개
- AU proxy 35개: MediaPipe blendshape category 이름을 사전순으로 정렬하지 않고 아래 고정
  순서로 선택한다.

```text
browDownLeft, browDownRight, browInnerUp, browOuterUpLeft, browOuterUpRight,
cheekPuff, cheekSquintLeft, cheekSquintRight, eyeBlinkLeft, eyeBlinkRight,
eyeLookDownLeft, eyeLookDownRight, eyeLookInLeft, eyeLookInRight,
eyeLookOutLeft, eyeLookOutRight, eyeLookUpLeft, eyeLookUpRight,
eyeSquintLeft, eyeSquintRight, eyeWideLeft, eyeWideRight, jawOpen,
mouthClose, mouthFrownLeft, mouthFrownRight, mouthFunnel, mouthPucker,
mouthSmileLeft, mouthSmileRight, mouthStretchLeft, mouthStretchRight,
mouthUpperUpLeft, mouthUpperUpRight, noseSneerLeft
```

각 0.5초 구간에서 49개 특징의 평균과 모집단 표준편차를 계산해 98차원 토큰을 만든다.
10초 영상 한 개의 최종 입력 크기는 `[20, 98]`이다. Python 전처리와 브라우저 추론은 하나의
버전이 명시된 특징 계약을 각각 구현한다. 테스트 코드 안의 결정적 숫자 입력으로 Python과
TypeScript의 수치 동등성을 검증하되, 실행 시 생성되는 별도 샘플 데이터나 합성 영상은 두지 않는다.

특징 추출은 초당 10프레임을 균등 샘플링한다. 얼굴이 검출되지 않은 프레임은 통계에서 제외하고,
0.5초 구간마다 5개 중 최소 3개의 유효 프레임을 요구한다. 한 구간이라도 기준을 만족하지 못하면
해당 클립을 학습 특징에서 제외하고 이유를 전처리 보고서에 기록한다. 전체 clip의 5%를 초과해
제외되면 전처리 명령을 실패 처리한다. 웹에서는
유효한 10초 창이 준비될 때까지 추론하지 않고 얼굴을 화면 안에 위치시키라는 상태를 표시한다.

## 5. 학습 파이프라인

파이프라인은 다음 단계로 분리한다.

1. 데이터 계약과 분할 무결성을 검증한다.
2. MediaPipe Face Landmarker로 각 영상을 처리하고 `[20, 98]` 특징을 캐시한다.
3. Train 특징만 사용해 특징별 평균과 표준편차를 계산한다.
4. 논문 기반 Transformer를 학습하고 Validation macro F1 기준으로 최적 체크포인트를 선택한다.
5. 고정된 Test 분할에서 accuracy, macro F1, 클래스별 precision/recall/F1, confusion matrix를
   한 번 계산한다.
6. 최적 PyTorch 체크포인트를 ONNX로 변환하고 PyTorch와 ONNX Runtime 출력의 허용 오차를
   검증한다.

모델 입력은 `[batch, 20, 98]`이고 출력은 `[batch, 4]` logits다. 98차원 토큰을 256차원으로
선형 투영한 뒤 학습 가능한 위치 임베딩, 8-head Transformer encoder 4개, global max pooling,
128-unit ReLU MLP, 4-class 출력층을 적용한다. 논문의 표에 맞춰 attention head size 256과
dropout 0.3을 기본값으로 사용하되 모든 학습 하이퍼파라미터와 seed를 실행 결과에 저장한다.

논문 베이스라인과 같이 기본 손실은 class weight가 없는 cross entropy로 둔다. 후속 비교 실험을
위해 Train 분할에서만 계산한 inverse-frequency class weight를 명시적 옵션으로 제공하지만 기본값은
비활성화한다. 조기 종료와 체크포인트 선택에는 Test 결과를 사용하지 않는다. 실행 산출물에는 체크포인트,
ONNX 모델, 특징 스키마, 클래스 순서, 정규화 통계, 하이퍼파라미터, 지표와 제외된 클립 보고서를
함께 저장한다.

## 6. ONNX 경계

정규화 평균과 표준편차는 PyTorch 모델의 buffer로 포함한다. 따라서 ONNX 모델은 웹에서 계산한
원시 `[1, 20, 98]` 특징을 직접 입력받고 내부에서 학습 시점과 동일하게 정규화한다.

ONNX에는 MediaPipe 자체를 포함하지 않는다. Python과 웹은 동일한 `mediapipe_98_v1` 계약에
따라 특징을 만들고, ONNX는 Transformer 분류만 담당한다. 모델 메타데이터에는 특징 스키마,
입력 shape, 클래스 순서, 구간 수와 창 길이를 기록한다. 웹이 지원하지 않는 스키마의 모델을
불러오면 추론을 시작하지 않는다.

## 7. 웹 추론 페이지

웹은 Vanilla TypeScript와 Vite 기반의 독립적인 작은 앱으로 구성한다. MediaPipe Tasks Vision이
웹캠 프레임에서 얼굴 landmark, blendshape와 얼굴 변환 행렬을 계산하고, ONNX Runtime Web이
내보낸 Transformer를 실행한다. 버전이 고정된 MediaPipe WASM과 Face Landmarker 모델은 앱
시작 시 공식 배포 URL에서 내려받지만 웹캠 프레임이나 추출 특징은 전송하지 않는다.

페이지에는 다음 요소만 제공한다.

- 웹캠 미리보기와 얼굴 landmark overlay
- 카메라 시작/중지 버튼
- 최근 10초 창의 수집 진행률
- 현재 참여도 클래스와 4개 클래스 확률
- 얼굴 미검출, 모델 불일치, 카메라 권한 거부 등의 상태 메시지
- 최근 예측의 짧은 시간 추이

추론은 최근 10초 rolling window를 사용한다. 창이 준비된 뒤에는 설정된 간격마다 새 예측을
계산하되 이전 창 전체를 다시 MediaPipe로 처리하지 않는다. 웹캠 프레임과 특징은 네트워크로
전송하거나 브라우저 영구 저장소에 기록하지 않는다.

## 8. 확장성과 비교 실험

특징 선택은 버전이 있는 schema registry로 격리한다. 후속 `mediapipe_132_v1`은 Gaze 8개,
Head Pose 6개, 전체 blendshape 52개를 사용하고 구간 통계를 적용해 132차원 토큰을 만든다.

98차원과 132차원 모델은 같은 데이터 분할, 전처리 검출 기준, seed, optimizer, epoch budget,
평가 지표를 사용해 비교한다. 특징 차원은 모델 설정과 ONNX 메타데이터에서 읽으므로 웹 UI와
rolling-window 로직은 수정하지 않고 지원 schema만 추가할 수 있다.

## 9. 오류 처리

- 데이터가 없으면 필요한 디렉터리와 파일 목록을 포함한 오류로 종료한다.
- 잘못된 레이블, 중복 분할, 누락 영상은 전처리 전에 한꺼번에 보고한다.
- 일부 영상의 codec 또는 MediaPipe 처리 실패는 실패 보고서에 기록하며, 실패율이 허용 기준을
  넘으면 전체 전처리를 실패시킨다.
- NaN 또는 무한대 특징은 캐시에 저장하지 않는다.
- ONNX 변환 후 출력이 PyTorch 출력과 허용 오차를 넘으면 배포 산출물을 만들지 않는다.
- 웹에서는 카메라 권한, MediaPipe 모델, ONNX 모델을 각각 독립적으로 검사하고 해결 가능한
  한국어 메시지를 표시한다.

## 10. 테스트와 검증

Python은 pytest로 다음을 검증한다.

- 공식 레이블 매핑과 데이터 계약 검증
- 분할 중복 및 누락 파일 탐지
- gaze/head pose/blendshape 선택과 20구간 통계
- 얼굴 미검출 구간 처리
- Transformer 입력·출력 shape 및 정규화 buffer
- 학습 결과 메타데이터와 지표 형식
- ONNX export 및 ONNX Runtime 수치 동등성

웹은 Vitest로 특징 집계, rolling window, 모델 메타데이터 검증과 상태 전이를 검증한다. Python과
TypeScript의 공통 숫자 fixture로 98차원 토큰이 허용 오차 안에서 일치하는지 확인한다. 브라우저
수동 검증에서는 카메라 권한 허용·거부, 얼굴 등장·이탈, 10초 창 완료와 예측 갱신을 확인한다.

실제 EngageNet 성능 수치는 데이터셋이 준비된 뒤에만 주장한다. 데이터가 없는 현재 환경에서는
정적 검사, 단위 테스트, 모델 forward/export smoke test와 웹 빌드를 완료 기준으로 삼는다.

## 11. 완료 기준

- 데이터셋이 배치되면 별도 코드 수정 없이 검증, 특징 추출, 학습, 평가, ONNX export를 실행할
  수 있다.
- 데이터가 없을 때 샘플이나 합성 데이터를 생성하지 않고 명확히 종료한다.
- Python과 웹의 `mediapipe_98_v1` 특징 토큰 계산이 수치적으로 일치한다.
- PyTorch와 ONNX Runtime 출력이 지정된 허용 오차 안에서 일치한다.
- 웹 페이지가 카메라 영상을 외부로 전송하지 않고 최근 10초 창으로 실시간 추론한다.
- 132차원 후속 스키마를 기존 학습·웹 구조의 재작성 없이 추가할 수 있다.
