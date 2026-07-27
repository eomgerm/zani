# 원격 L40S 학습 가이드

JupyterHub 위의 L40S 서버에서 E1(ST-GCN)을 학습하는 절차입니다. 로컬 Windows에서의
실행은 [README](../README.md)를 따르고, 이 문서는 원격에서 달라지는 부분만 다룹니다.

## 대상 환경

아래는 실측값입니다. 다른 서버라면 [사전 확인](#사전-확인)을 먼저 돌려 비교하세요.

| 항목 | 값 |
|---|---|
| OS | Ubuntu 24.04.3, kernel 6.8, glibc 2.39 |
| CPU / RAM | 128 cores / 1007 GB |
| GPU | NVIDIA L40S × 4 (각 45 GB, compute capability 8.9) |
| Driver | 570.211.01 → **CUDA 12.8 상한** |
| Jupyter | TLJH, `jupyterhub_idle_culler --timeout=86400` |
| 접근 | JupyterHub 웹만. **SSH 계정 접근 불가** |
| 디스크 | `/home` 18 TB, `/dev/shm` 504 GB |

CUDA 13 런타임은 드라이버 580 이상을 요구하므로 이 서버에서는 쓸 수 없습니다.
`pyproject.toml`이 리눅스 torch를 cu128로 고정하는 이유입니다.

## 사전 확인

새 서버에 처음 붙었다면 Jupyter 터미널에서 확인합니다.

```bash
nvidia-smi --query-gpu=index,name,driver_version,memory.total,memory.used,compute_cap --format=csv
```

```bash
cat /etc/os-release | head -2; echo "cores=$(nproc) ram=$(free -g | awk '/Mem:/{print $2}')GB"; df -h /home /dev/shm; ulimit -n
```

```bash
env | grep -i proxy; for u in https://pypi.org/simple/ https://download.pytorch.org/whl/cu128/ https://lab.ssafy.com; do printf '%-46s ' "$u"; curl -sS -m 10 -o /dev/null -w '%{http_code}\n' "$u" || echo BLOCKED; done
```

## 1. 코드 받기

```bash
mkdir -p ~/zani && git clone -b 'ai/feat/e1-stgcn-랜드마크-그래프-S15P11A105-163' https://lab.ssafy.com/s15-webmobile1-sub1/S15P11A105.git ~/zani && cd ~/zani && git log --oneline -1
```

HTTPS 인증은 GitLab 사용자명과 Personal Access Token을 씁니다.

## 2. 환경 구성

TLJH의 공용 파이썬(`/opt/tljh/user/`)은 다른 사용자와 공유하므로 **여기에 설치하지
않습니다.** `uv`가 프로젝트 `.venv`를 홈 아래에 만들고 Python 3.12도 알아서 받아옵니다.

```bash
cd ~/zani/ai && uv sync --extra train --group dev
```

MediaPipe(`--extra vision`)는 원본 영상에서 특징을 다시 뽑을 때만 필요합니다.
`reproduce-e1`·`finalize-e1` 경로는 MediaPipe를 임포트하지 않으므로 처음에는 생략하세요.

## 3. 사용할 GPU 고정

허가받은 카드만 노출시킵니다. 이 서버에서는 2번입니다.

```bash
export CUDA_VISIBLE_DEVICES=2
```

`CUDA_VISIBLE_DEVICES`로 좁히면 PyTorch는 그 카드를 `cuda:0`으로 봅니다. `--device cuda:N`도
받지만, 다른 사용자의 작업을 침범하지 않으려면 환경변수로 막아두는 쪽이 안전합니다.

```bash
cd ~/zani/ai && CUBLAS_WORKSPACE_CONFIG=:4096:8 uv run python -c "import torch;print(torch.__version__, torch.version.cuda, torch.cuda.device_count(), torch.cuda.get_device_name(0), torch.cuda.get_device_capability(0))"
```

`device_count`가 `1`, 이름이 `NVIDIA L40S`, capability가 `(8, 9)`여야 정상입니다.

## 4. 데이터 반입

SSH가 없으므로 `rsync`/`scp`를 쓸 수 없고 JupyterLab 파일 업로드를 씁니다. 특징 파일이
1만 개가 넘으므로 반드시 **tar 하나로 묶어** 올립니다.

로컬(PowerShell)에서 학습에 필요한 것만 먼저 묶습니다.

```powershell
cd <repo>\ai\datasets\processed\engagenet; tar -cf "$env:USERPROFILE\Downloads\e1.tar" e1 landmark_78_v1_graph.npz landmark_78_v1_graph.npz.json
```

JupyterLab 파일 브라우저로 홈에 업로드한 뒤 풉니다. **경로가 중요합니다** — graph는
features 디렉터리의 부모에서 자동으로 찾습니다.

```bash
mkdir -p ~/zani/ai/datasets/processed/engagenet && tar -xf ~/e1.tar -C ~/zani/ai/datasets/processed/engagenet && rm ~/e1.tar && ls ~/zani/ai/datasets/processed/engagenet
```

표현을 바꿔가며 실험하려면 `raw_frames_v1`(5.2 GB)도 같은 방식으로 올리고
`--extra vision`을 추가 설치한 뒤 `build-features`를 실행합니다. 원본 MP4(31.5 GB)는
GPU가 기여하지 않는 구간이라 반입하지 않습니다.

## 5. 학습 실행

idle culler가 24시간 뒤 singleuser 서버를 내리고, 그 자식 프로세스는 함께 죽습니다.
`setsid`로 세션을 분리해야 장시간 실행이 살아남습니다. 브라우저를 닫아도 됩니다.

```bash
cd ~/zani/ai && CUDA_VISIBLE_DEVICES=2 CUBLAS_WORKSPACE_CONFIG=:4096:8 setsid nohup uv run python -m zani_ai engagement reproduce-e1 --features datasets/processed/engagenet/e1 --output artifacts/engagement/e1 --device cuda > ~/e1.log 2>&1 < /dev/null & echo "PID=$!"
```

```bash
tail -f ~/e1.log
```

GPU 사용률을 함께 보면 병목을 알 수 있습니다.

```bash
nvidia-smi --query-gpu=index,utilization.gpu,memory.used --format=csv -l 5 -i 2
```

중단해도 완료된 seed는 재사용되므로 같은 명령을 다시 실행하면 이어집니다.

Test 평가와 HTML 리포트는 5개 seed가 모두 끝난 뒤 한 번만 실행합니다.

```bash
cd ~/zani/ai && CUDA_VISIBLE_DEVICES=2 uv run python -m zani_ai engagement finalize-e1 --features datasets/processed/engagenet/e1 --output artifacts/engagement/e1 --device cuda
```

## landmark graph 경로

E1은 ST-GCN 노드 토폴로지를 정의하는 `landmark_78_v1_graph.npz`가 필요합니다. 찾는 순서는
다음과 같고, 모두 실패하면 시도한 경로를 전부 출력합니다.

1. `--graph <경로>`
2. 환경변수 `ZANI_LANDMARK_GRAPH`
3. `<--features>/landmark_78_v1_graph.npz`
4. `<--features의 부모>/landmark_78_v1_graph.npz`

`--graph`나 환경변수를 지정했는데 파일이 없으면 다음 후보로 넘어가지 않고 바로 실패합니다.
지정한 의도를 조용히 무시하면 잘못된 graph로 학습하는 사고가 납니다.

graph의 SHA-256은 `summary.json`의 `inputs.landmark_graph`에 기록되고, 다른 graph로
같은 출력 디렉터리를 이어받으려 하면 거부됩니다. graph가 바뀌면 노드 토폴로지가 바뀌므로
기존 seed와 비교할 수 없기 때문입니다.

> 이 파일을 생성하는 CLI 명령은 아직 없습니다. `build_spatial_partitions`가 데이터셋 평균
> 랜드마크 좌표를 입력으로 받으므로 상수만으로 재생성할 수 없습니다. **파일을 잃으면 E1
> 재현이 불가능하니 별도로 보관하세요.**

## 다른 환경에서 이어받기

`reproduce-*`는 기본적으로 실행 환경이 정확히 일치할 때만 기존 summary를 이어받습니다.
같은 서버에서 다른 카드를 배정받았거나 Python 패치 버전이 올라간 경우에는
`--allow-environment-drift`로 계속할 수 있습니다.

```bash
uv run python -m zani_ai engagement reproduce-e1 --features <root> --output <dir> --device cuda --allow-environment-drift
```

이때도 결과 수치를 바꾸는 항목 — PyTorch 버전, CUDA 런타임, `CUBLAS_WORKSPACE_CONFIG`,
device 종류 — 은 여전히 정확히 일치해야 합니다. 완화되는 것은 Python 패치 버전과 어느
물리 카드였는지뿐이며, seed마다 실제 실행 환경이 `summary.json`에 기록되므로 무엇이
어디서 돌았는지는 그대로 추적됩니다.

**로컬 Windows에서 시작한 run은 이 서버에서 이어받을 수 없습니다.** 드라이버 제약 때문에
Windows는 `torch 2.13.0+cu130`, 리눅스는 `torch 2.11.0+cu128`을 쓰므로 PyTorch 버전이
다릅니다. 서버에서는 새 출력 디렉터리로 처음부터 실행하세요.

## 트러블슈팅

**`torch.cuda.is_available()`이 False** — `nvidia-smi`의 `CUDA Version`과 설치된 torch의
CUDA 빌드를 비교하세요. 드라이버가 지원하는 상한보다 높은 CUDA 런타임이면 초기화에
실패합니다. 이 서버는 드라이버 570.211.01 → cu128까지입니다.

**`requested device=cuda:N, but only M CUDA device(s) are visible`** —
`CUDA_VISIBLE_DEVICES`가 카드를 좁힌 뒤에는 인덱스가 0부터 다시 매겨집니다.
`CUDA_VISIBLE_DEVICES=2`를 걸었다면 `--device cuda` 또는 `--device cuda:0`입니다.

**학습이 조용히 사라짐** — `setsid` 없이 실행하면 idle culler가 singleuser 서버를 내릴 때
함께 종료됩니다. 5번 항목의 실행 형태를 그대로 쓰세요.

**파일 디스크립터 부족** — `ulimit -n`이 4096입니다. 여러 실행을 동시에 띄운다면
`ulimit -n 65536`으로 올리세요.
