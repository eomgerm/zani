# ZANI AI

MediaPipe 기반 학습 반응 특징 추출과 PyTorch 모델 학습을 위한 ZANI의 Python 모듈입니다.
현재 단계에는 재현 가능한 프로젝트 골격만 포함합니다.

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
- `train`: PyTorch, scikit-learn
- `dev`: pytest, coverage, Ruff, mypy

## 저장소 정책

소스 코드, 테스트, `pyproject.toml`, `uv.lock`, `.python-version`, 문서는 Git에 포함합니다.
가상환경, 데이터셋, 추출된 생체 특징, 모델 가중치, 체크포인트, 실험 로그는 포함하지 않습니다.
