#!/usr/bin/env bash
# Run one protocol's seeds as concurrent processes, then rebuild the summary.
#
# Each worker owns its seed directory and never writes summary.json, so the
# processes cannot clobber one another; `--collect-only` assembles the summary
# once they finish. Results are identical to a sequential run.
#
#   scripts/run_seeds_parallel.sh e1a datasets/processed/engagenet/e1 \
#       artifacts/engagement/e1a 42 43 44 45 46
#
# Environment:
#   CUDA_VISIBLE_DEVICES  card to expose (default 2 -- the allocated L40S)
#   LOG_DIR               where per-seed logs go (default ~/logs)
#   MAX_PARALLEL          seeds to run at once (default: all of them)
#   RESULTS_WORKTREE      publish metrics here when the run ends (default: skip)
#
# Detach it so a JupyterHub idle cull cannot take the run down with the server:
#   setsid nohup scripts/run_seeds_parallel.sh ... > ~/e1a.log 2>&1 < /dev/null &

set -euo pipefail

if [ "$#" -lt 4 ]; then
    echo "usage: $0 <protocol> <features-root> <output-dir> <seed> [seed ...]" >&2
    echo "  protocol: the reproduce-<protocol> suffix, e.g. e1a" >&2
    exit 2
fi

protocol="$1"
features="$2"
output="$3"
shift 3
seeds=("$@")

: "${CUDA_VISIBLE_DEVICES:=2}"
: "${LOG_DIR:=$HOME/logs}"
: "${MAX_PARALLEL:=${#seeds[@]}}"
export CUDA_VISIBLE_DEVICES
# Required for deterministic cuBLAS matmul; must be set before CUDA starts.
export CUBLAS_WORKSPACE_CONFIG="${CUBLAS_WORKSPACE_CONFIG:-:4096:8}"

mkdir -p "$LOG_DIR"

echo "protocol=$protocol seeds=${seeds[*]} gpu=$CUDA_VISIBLE_DEVICES parallel=$MAX_PARALLEL"
echo "logs=$LOG_DIR/${protocol}-seed-<n>.log"

failed=0
running=0

for seed in "${seeds[@]}"; do
    log="$LOG_DIR/${protocol}-seed-${seed}.log"
    uv run python -m zani_ai engagement "reproduce-${protocol}" \
        --features "$features" \
        --output "$output" \
        --device cuda \
        --seed "$seed" \
        > "$log" 2>&1 &
    echo "  seed=$seed pid=$! -> $log"

    running=$((running + 1))
    if [ "$running" -ge "$MAX_PARALLEL" ]; then
        # `wait -n` returns as soon as any one worker exits, keeping the
        # requested number in flight instead of draining the whole batch.
        wait -n || failed=1
        running=$((running - 1))
    fi
done

while [ "$running" -gt 0 ]; do
    wait -n || failed=1
    running=$((running - 1))
done

if [ "$failed" -ne 0 ]; then
    echo "at least one seed failed; see $LOG_DIR/${protocol}-seed-*.log" >&2
    echo "the summary below therefore covers only the seeds that completed" >&2
fi

echo "--- collecting summary ---"
uv run python -m zani_ai engagement "reproduce-${protocol}" \
    --features "$features" \
    --output "$output" \
    --device cuda \
    --collect-only

# The periodic publisher shares the singleuser server's cgroup, so an idle cull
# can take it down before the last seed lands. Publishing once here makes the
# final metrics independent of whether that loop is still alive.
if [ -n "${RESULTS_WORKTREE:-}" ]; then
    echo "--- publishing metrics ---"
    uv run python -m zani_ai engagement publish-results \
        --artifacts "$(dirname "$output")" \
        --worktree "$RESULTS_WORKTREE" \
        --interval 0 \
        || echo "publish failed; the periodic publisher will retry" >&2
fi

exit "$failed"
