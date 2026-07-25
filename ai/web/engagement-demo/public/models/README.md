# 브라우저 추론 모델

이 디렉터리에는 학습된 모델이 포함되어 있지 않습니다. EngageNet 학습을 완료한 뒤 저장소
`ai/` 디렉터리에서 다음 명령을 실행하면 웹 앱이 요구하는 두 파일이 생성됩니다.

```powershell
uv run python -m zani_ai engagement export `
  --checkpoint artifacts/engagement/run-001/best.pt `
  --output web/engagement-demo/public/models
```

생성 파일:

- `engagement.onnx`
- `engagement.metadata.json`

ONNX 모델은 `.gitignore`에 의해 제외됩니다. 실제 모델이 없으면 웹 페이지는 추론값을
만들지 않고 모델 준비 안내만 표시합니다.
