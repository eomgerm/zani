# Remote L40S Training Guide

How to run an engagement protocol on the L40S server behind JupyterHub. Follow
[`ai/README.md`](../ai/README.md) for local Windows runs; this document covers
only what differs on the remote server.

## Target environment

The values below are measured. On a different server, run the
[pre-flight checks](#pre-flight-checks) first and compare.

| Item | Value |
|---|---|
| OS | Ubuntu 24.04.3, kernel 6.8, glibc 2.39 |
| CPU / RAM | 128 cores / 1007 GB |
| GPU | NVIDIA L40S × 4 (45 GB each, compute capability 8.9) |
| Driver | 570.211.01 → **CUDA 12.8 ceiling** |
| Jupyter | TLJH, `jupyterhub_idle_culler --timeout=86400` |
| Access | JupyterHub web only. **No SSH account access** |
| Disk | `/home` 18 TB, `/dev/shm` 504 GB |

A CUDA 13 runtime requires driver 580 or newer, so it cannot be used on this
server. That is why `pyproject.toml` pins Linux torch to cu128.

## Pre-flight checks

The first time you attach to a new server, run these in a Jupyter terminal.

```bash
nvidia-smi --query-gpu=index,name,driver_version,memory.total,memory.used,compute_cap --format=csv
```

```bash
cat /etc/os-release | head -2; echo "cores=$(nproc) ram=$(free -g | awk '/Mem:/{print $2}')GB"; df -h /home /dev/shm; ulimit -n
```

```bash
env | grep -i proxy; for u in https://pypi.org/simple/ https://download.pytorch.org/whl/cu128/ https://lab.ssafy.com; do printf '%-46s ' "$u"; curl -sS -m 10 -o /dev/null -w '%{http_code}\n' "$u" || echo BLOCKED; done
```

## 1. Getting the code

```bash
mkdir -p ~/zani && git clone -b dev https://lab.ssafy.com/s15-webmobile1-sub1/S15P11A105.git ~/zani && cd ~/zani && git log --oneline -1
```

If the work you are running lives on a feature branch, name that branch instead
of `dev`.

HTTPS authentication uses your GitLab username and a Personal Access Token.

## 2. Setting up the environment

TLJH's shared Python (`/opt/tljh/user/`) is shared with other users, so **do not
install into it.** `uv` creates the project `.venv` under your home directory and
fetches Python 3.12 on its own.

```bash
cd ~/zani/ai && uv sync --extra vision --extra train --group dev
```

`--extra vision` is required even when you only train and do not re-extract
features. `experiment.py` imports `representations`, which imports `extraction`
at module top level, and `extraction` uses `cv2`. `cv2` arrives as a MediaPipe
dependency, so with only `--extra train` installed, `reproduce-*` and
`finalize-*` die during import with
`ModuleNotFoundError: No module named 'cv2'`. `uv sync` removes packages that
are not selected, so re-syncing without the extra puts a previously working
environment into the same state.

## 3. Pinning the GPU

Expose only the card you were allocated. On this server that is card 2.

```bash
export CUDA_VISIBLE_DEVICES=2
```

Once `CUDA_VISIBLE_DEVICES` narrows the selection, PyTorch sees that card as
`cuda:0`. `--device cuda:N` is also accepted, but pinning through the
environment variable is safer because it cannot reach into another user's work.

```bash
cd ~/zani/ai && CUBLAS_WORKSPACE_CONFIG=:4096:8 uv run python -c "import torch;print(torch.__version__, torch.version.cuda, torch.cuda.device_count(), torch.cuda.get_device_name(0), torch.cuda.get_device_capability(0))"
```

A healthy result is `device_count` of `1`, name `NVIDIA L40S`, and capability
`(8, 9)`.

## 4. Bringing in data

There is no SSH, so `rsync`/`scp` are unavailable and you use JupyterLab file
upload. There are more than ten thousand feature files, so you must **pack them
into a single tar** first.

Locally (PowerShell), pack only what training needs.

```powershell
cd <repo>\ai\datasets\processed\engagenet; tar -cf "$env:USERPROFILE\Downloads\e1.tar" e1 landmark_78_v1_graph.npz landmark_78_v1_graph.npz.json
```

Upload it to your home directory with the JupyterLab file browser, then unpack.
**The path matters** — the graph is discovered automatically from the parent of
the features directory.

```bash
mkdir -p ~/zani/ai/datasets/processed/engagenet && tar -xf ~/e1.tar -C ~/zani/ai/datasets/processed/engagenet && rm ~/e1.tar && ls ~/zani/ai/datasets/processed/engagenet
```

To experiment across representations, upload `raw_frames_v1` (5.2 GB) the same
way and then run `build-features`. Its `--schema` accepts four representations:
`mediapipe_98_v1`, `mediapipe_132_v1`, `landmark_78_v1`, and
`landmark_78_300_v1`; the last of these needs `--sample-fps 30`, because the
schema name has to match the step count the sampling rate produces and the
default `--sample-fps 10` produces `landmark_78_v1`. `build-features` writes the
cache to `<--output>/<schema>/`. The original MP4s (31.5 GB) are not brought in,
because that stage is not one the GPU contributes to.

`audit-frame-gate` counts raw clips accepted offline but rejected by the browser
frame gate, which is how you size the mismatch band before acting on it. It
takes `--raw-root` and `--output` alongside the shared data options, and the
result JSON records the counts overall, per split and per label, together with
the clip IDs.

```bash
cd ~/zani/ai
uv run python -m zani_ai engagement audit-frame-gate \
  --data-root datasets/raw/engagenet \
  --raw-root datasets/processed/engagenet/raw_frames_v1 \
  --output artifacts/engagement/frame-gate-audit.json
```

## 5. Running training

The idle culler stops the singleuser server after 24 hours, and its child
processes die with it. Run training under `setsid nohup` as shown below, and read
[Long runs and the idle culler](#long-runs-and-the-idle-culler) before starting a
run you cannot cheaply restart.

Commands take the form `reproduce-<protocol>` and `finalize-<protocol>`. The
protocol list is `SPECS` in `ai/src/zani_ai/engagement/experiment.py`, and
`uv run python -m zani_ai engagement --help` prints every registered subcommand.
Substitute `<protocol>` below with the CLI protocol name you are running (`e1`,
`e1a`, `e0c`, …); `--features` must point at the feature representation that
protocol expects, which for the ST-GCN protocols is the `e1` directory unpacked
above. Some protocols take an extra required argument — `reproduce-e0i` needs
`--reliability <reliability_manifest.json>`, produced by
`analyze-label-reliability` — so check `--help` for the protocol you are running.

```bash
cd ~/zani/ai && CUDA_VISIBLE_DEVICES=2 CUBLAS_WORKSPACE_CONFIG=:4096:8 setsid nohup uv run python -m zani_ai engagement reproduce-<protocol> --features datasets/processed/engagenet/e1 --output artifacts/engagement/<protocol> --device cuda > ~/<protocol>.log 2>&1 < /dev/null & echo "PID=$!"
```

```bash
tail -f ~/<protocol>.log
```

Watching GPU utilization alongside it tells you where the bottleneck is.

```bash
nvidia-smi --query-gpu=index,utilization.gpu,memory.used --format=csv -l 5 -i 2
```

Test evaluation and the HTML report run exactly once, after all five seeds have
finished.

```bash
cd ~/zani/ai && CUDA_VISIBLE_DEVICES=2 uv run python -m zani_ai engagement finalize-<protocol> --features datasets/processed/engagenet/e1 --output artifacts/engagement/<protocol> --device cuda
```

### Long runs and the idle culler

`setsid` detaches the controlling terminal, so closing the terminal widget does
not end a run. It does **not** save a run from the idle culler. Measured on this
server:

| Check | Value |
|---|---|
| `KillMode` of `jupyter-<user>.service` | `control-group` |
| `Delegate` | `no` |
| `setsid` session | detached (`sid == pid`) |
| `setsid` cgroup | still `jupyter-<user>.service` |
| user crontab | `Permission denied` |
| `systemd-run --user` | `Failed to connect to bus` |

Stopping the unit kills every process in its cgroup, and `setsid` does not leave
a cgroup. All three escapes are closed, so a culled server takes the training
with it.

Re-measure with:

    unit=$(awk -F/ '{print $NF}' /proc/self/cgroup); systemctl show -p KillMode -p Delegate "$unit"

**Keep a JupyterLab browser tab open for the duration of a long run.** The open
tab refreshes `last_activity`, so the idle timer never runs down. This lowers how
often a cull happens; it is not a guarantee — sleep, a dropped network or a
discarded background tab all stop it silently.

Because a cull is always possible, treat resuming as normal operation rather
than recovery. Completed seeds are reused, so re-running the same command
continues from where it stopped; at most one seed's progress is lost. Publish
metrics continuously so a cull cannot take them with it — see
[`ai-experiment-results-guide.md`](./ai-experiment-results-guide.md).

## Performance characteristics

Measured while running E1 sequentially at batch 32 on a single L40S.

| Metric | Value |
|---|---|
| `utilization.gpu` | 98~99% |
| `power.draw` | 311~315 W / 350 W |
| `memory.used` | 5,095 / 46,068 MiB |

**Do not run seeds in parallel on one card.** The rise from 33 W idle to 313 W
means the SMs really are saturated (`utilization.gpu` on its own cannot
distinguish that from small kernels running back to back). The work is compute
bound, so stacking several seeds onto one card only time-slices them and does not
reduce total time. Placing one seed per card is valid when several GPUs are
available.

The same applies to batch size. Nine times the memory is still free, but total
FLOPs are unchanged, so there is no gain and only `configuration_sha256` breaks.
If you want more throughput, the only lever left is precision (TF32 / BF16), and
because that changes the resulting numbers it has to be treated as a new
protocol.

## Parallel seed runs

The seeds of one protocol are independent of each other, so they can run
concurrently as separate processes. Each process writes only inside its own
`seed-<n>/` and does not touch `summary.json`; afterwards `--collect-only`
gathers the per-seed `record.json` files into the summary. **The results are
identical to a sequential run.**

```bash
cd ~/zani/ai && CUDA_VISIBLE_DEVICES=2 setsid nohup scripts/run_seeds_parallel.sh <protocol> datasets/processed/engagenet/e1 artifacts/engagement/<protocol> 42 43 44 45 46 > ~/<protocol>.log 2>&1 < /dev/null & echo "PID=$!"
```

Limit how many run at once with `MAX_PARALLEL`. If the GPU is already saturated,
stacking several of them only time-slices them, so judge from the power figures
in [Performance characteristics](#performance-characteristics) first.

```bash
MAX_PARALLEL=2 CUDA_VISIBLE_DEVICES=2 scripts/run_seeds_parallel.sh <protocol> <features> <output> 42 43 44 45 46
```

To do it by hand, pass `--seed` per seed and collect once at the end.

```bash
uv run python -m zani_ai engagement reproduce-<protocol> --features <root> --output <dir> --device cuda --seed 42
```

```bash
uv run python -m zani_ai engagement reproduce-<protocol> --features <root> --output <dir> --device cuda --collect-only
```

If two processes claim the same seed, `seed-<n>/.seed.lock` rejects the second
one immediately. After an interruption, re-running reuses completed seeds as they
are.

Passing `RESULTS_WORKTREE` to the script publishes metrics once more when the run
ends — see
[`ai-experiment-results-guide.md`](./ai-experiment-results-guide.md).

## Landmark graph lookup

The three protocols with `needs_landmark_graph=True` — **E1, E1-A and E1-B** —
require `landmark_78_v1_graph.npz`, which defines the ST-GCN node topology. It is
looked up in the following order, and if all of them fail, every path tried is
printed.

1. `--graph <path>`
2. The `ZANI_LANDMARK_GRAPH` environment variable
3. `<--features>/landmark_78_v1_graph.npz`
4. `<parent of --features>/landmark_78_v1_graph.npz`

Only `reproduce-e1` accepts `--graph`; for E1-A and E1-B, either point
`ZANI_LANDMARK_GRAPH` at the file or leave it beside or above `--features`.

If you specified `--graph` or the environment variable and the file is not there,
the lookup fails immediately instead of moving on to the next candidate.
Silently ignoring a stated intent leads to training against the wrong graph.

The SHA-256 of the graph is recorded in `inputs.landmark_graph` in
`summary.json`, and resuming into the same output directory with a different
graph is refused. A different graph means a different node topology, so it cannot
be compared against the existing seeds.

> There is no CLI command that produces this file yet.
> `build_spatial_partitions` takes the dataset's mean landmark coordinates as
> input, so it cannot be regenerated from constants alone. **Losing the file
> makes the ST-GCN protocols irreproducible, so keep a separate copy.**

## Resuming in a different environment

By default `reproduce-*` resumes an existing summary only when the execution
environment matches exactly. When you were allocated a different card on the same
server, or the Python patch version moved up, `--allow-environment-drift` lets
you continue.

```bash
uv run python -m zani_ai engagement reproduce-<protocol> --features <root> --output <dir> --device cuda --allow-environment-drift
```

Even then, the items that change the resulting numbers — PyTorch version, CUDA
runtime, `CUBLAS_WORKSPACE_CONFIG`, device type — still have to match exactly.
What is relaxed is only the Python patch version and which physical card it was,
and because each seed records its actual execution environment in
`summary.json`, what ran where remains fully traceable.

**A run started on local Windows cannot be resumed on this server.** Because of
the driver constraint, Windows uses `torch 2.13.0+cu130` and Linux uses
`torch 2.11.0+cu128`, so the PyTorch versions differ. On the server, start from
scratch in a new output directory.

## Troubleshooting

**`torch.cuda.is_available()` is False** — compare the `CUDA Version` from
`nvidia-smi` against the CUDA build of the installed torch. A CUDA runtime higher
than the ceiling the driver supports fails to initialize. This server is driver
570.211.01 → cu128 at most.

**`requested device=cuda:N, but only M CUDA device(s) are visible`** — once
`CUDA_VISIBLE_DEVICES` has narrowed the cards, indexes are renumbered from 0. If
you set `CUDA_VISIBLE_DEVICES=2`, it is `--device cuda` or `--device cuda:0`.

**Training silently disappears** — without `setsid` a run ends as soon as the
terminal widget goes away. With `setsid` it survives that, but not a cull: the
idle culler stops the singleuser unit and `KillMode=control-group` kills
everything in its cgroup, detached sessions included. See
[Long runs and the idle culler](#long-runs-and-the-idle-culler), then resume the
run.

**Out of file descriptors** — `ulimit -n` is 4096. If you launch several runs at
once, raise it with `ulimit -n 65536`.
