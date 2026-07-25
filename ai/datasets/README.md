# 로컬 데이터셋

이 디렉터리는 ZANI AI의 로컬 개발과 학습에 사용하는 데이터셋을 저장합니다.
데이터 파일은 Git에 커밋하지 않습니다.

## 공식 구조

```text
datasets/
├─ raw/engagenet/
│  ├─ final_labels.csv
│  ├─ train.txt
│  ├─ valid.txt
│  ├─ test.txt
│  └─ videos/<clip-id>.mp4
└─ processed/engagenet/    # MediaPipe 특징 배열과 manifest
```

EngageNet 데이터셋은 저자의 접근 조건에 따라 별도로 취득해야 합니다. 참여자 영상,
식별자를 포함한 레이블, 추출한 생체 특징, 파생 샘플을 Git에 커밋하지 마세요.
재현에 필요한 취득 및 전처리 과정은 문서나 스크립트로 기록합니다.

특징 추출 결과는 `processed/engagenet/mediapipe_98_v1/<split>/<clip-id>.npz`에
저장되며 `manifest.json`이 포함·제외된 클립과 제외 이유를 기록합니다. 이 결과도 Git에
포함하지 않습니다.
