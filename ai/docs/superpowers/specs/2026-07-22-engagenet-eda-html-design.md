# EngageNet HTML EDA 리포트 설계

## 목표

`datasets/`에 있는 EngageNet 영상, 정답 라벨, MARLIN 특징을 전수 검사해 ML
엔지니어가 학습 전 데이터 품질과 분포를 판단할 수 있는 단일 HTML 리포트를 만든다.
원본 데이터는 수정하지 않으며 대용량 영상 프레임 전체를 메모리에 적재하지 않는다.

## 산출물

- `artifacts/eda/engagenet_eda_report.html`: Plotly가 포함된 자체 완결형 HTML 리포트
- `artifacts/eda/engagenet_eda_records.csv`: 리포트 수치를 재검증할 수 있는 클립 단위 요약
- `scripts/generate_engagenet_eda.py`: 동일한 폴더 구조에서 리포트를 다시 생성하는 CLI

## 입력 계약

- 영상: `datasets/{Train,Validation,Test}/*.mp4`
- MARLIN 특징: `datasets/MARLIN_{Train,Validation,Test}/*.mp4.pt`
- 라벨:
  - `datasets/train_engagement_labels.xlsx`
  - `datasets/validation_engagement_labels.xlsx`
  - `datasets/test_engagement_labels.csv`
- 조인 키는 확장자를 포함한 영상 파일명인 `chunk`로 고정한다.
- 라벨의 저장 순서나 불필요한 인덱스 열에는 의존하지 않는다.

## 분석 데이터 흐름

1. pandas와 openpyxl로 라벨을 읽고 `chunk`, `label`만 표준화한다.
2. 파일명에서 subject, 원본 video 번호, clip 번호를 파싱한다.
3. OpenCV로 컨테이너 헤더만 읽어 FPS, 프레임 수, 길이, 해상도, 코덱을 수집한다.
4. PyTorch로 MARLIN 텐서를 한 파일씩 CPU에 로드해 shape, dtype, 비유한값을 검사한다.
5. `chunk` 기준으로 라벨·영상·특징을 결합하고 split 간 subject 중복을 계산한다.
6. 클립 단위 요약 CSV를 저장한 뒤 같은 데이터프레임으로 HTML 표와 차트를 만든다.

## 리포트 구성

리포트 첫 화면에는 전체 클립 수, subject 수, 라벨/특징 매칭률, 원본 영상 및 특징
용량을 KPI로 표시한다. 이어서 다음 내용을 제공한다.

- split별 클립·subject·용량과 영상 메타데이터 요약
- 네 개 참여도 클래스의 건수와 비율, 불균형 비율
- subject 단위 split 누수와 중복 파일명 검사
- 영상 길이, FPS, 해상도, 파일 크기 분포
- MARLIN 시간 길이와 특징 차원 분포, dtype 및 비유한값 검사
- 라벨과 MARLIN 시간 길이의 교차 분포
- 누락, 로드 실패, 비정상 차원, 드문 시간 길이 등 검토 대상 샘플 목록
- 분석 방법, 경고 기준, 재실행 명령

차트는 Plotly를 HTML 안에 한 번만 내장해 오프라인에서도 동작하게 한다. 색상은 split과
라벨에 일관된 의미를 부여하고, 표에는 정확한 건수와 비율을 함께 둔다.

## 품질 규칙

- 라벨, 영상, MARLIN 파일의 양방향 매칭률을 각각 계산한다.
- 같은 subject가 둘 이상의 split에 있으면 오류로 표시한다.
- `feature_dim != 1024`, 비유한값, 텐서 로드 실패는 오류로 표시한다.
- 영상 헤더 열기 실패, FPS/프레임 수가 0 이하인 파일은 오류로 표시한다.
- 영상 길이와 MARLIN 시간 길이는 데이터 분포를 먼저 보여주며, 빈도가 낮다는 이유만으로
  삭제 대상으로 단정하지 않는다. 드문 값은 검토 대상으로만 표시한다.
- 클래스 불균형은 최대 클래스 수를 최소 클래스 수로 나눈 비율과 split별 비율로 보고한다.

## 오류 처리

개별 영상 또는 텐서 로드 실패는 전체 실행을 중단하지 않고 해당 클립에 오류 유형과 메시지를
기록한다. 필수 라벨 파일이나 필수 컬럼이 없거나 라벨 키가 중복되면 조인 결과가 잘못될 수
있으므로 명확한 오류를 내고 실행을 중단한다.

## 검증

- 리포트의 split별 클립 합계가 파일 시스템의 영상 수와 같은지 확인한다.
- 라벨 클래스 합계와 영상 수, MARLIN shape 합계가 일치하는지 확인한다.
- subject split 교집합과 파일명 교집합을 직접 재계산한다.
- HTML에 필수 섹션, Plotly 스크립트, 차트 컨테이너가 존재하는지 자동 검사한다.
- 로컬 브라우저에서 리포트를 열어 차트 렌더링, 가로 넘침, 표 가독성을 확인한다.
