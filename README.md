# ZANI 실험 지표

`reproduce-*` / `finalize-*` 실행이 남기는 지표 JSON만 담는 브랜치다. 코드 이력이 없는
오펀 브랜치이므로 어디에도 merge하지 않는다.

경로는 `<출처>/<프로토콜>/...` 이다. 첫 조각이 출처 라벨인 이유는 Windows(`torch cu130`)와
리눅스(`torch cu128`)의 결과를 섞으면 안 되기 때문이다. 라벨은 기계의 짧은 호스트명으로 기본 설정된다.

- `jupyter04/e1/summary.json`
- `jupyter04/e1/seed-42/record.json`

체크포인트(`best.pt`), ONNX, HTML 리포트는 넣지 않는다. 파일은 모두 생성물이므로 손으로
고치지 않는다.

쓰는 쪽·읽는 쪽 절차는 `.agents/ai-experiment-results-guide.md` 를 본다.
