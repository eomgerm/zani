# Experiment Results Branch Guide

## What the branch is

`ai/results` is an orphan branch that holds only the metric JSON that
`reproduce-*` and `finalize-*` runs leave behind: `summary.json`,
`test_results.json`, `record.json` and `metrics.json`.

It has no code history. That is deliberate — training happens on a remote server
whose JupyterHub idle culler stops the singleuser server, and anything living
only in that server's filesystem dies with it. Pushing metrics to a branch puts
them somewhere every local checkout can read.

Because it shares no history with `dev`, **never merge this branch anywhere.**
It is a data channel, not a line of development.

## Reading the metrics

The branch is normally checked out as a separate worktree. Find it:

```bash
git worktree list
```

Look for the line whose branch is `[ai/results]`. If there is none, create one at
any path you like:

```bash
git worktree add <path> ai/results
```

Bring it up to date before reading. The publisher rebases and pushes, so a
fast-forward is all that is ever needed:

```bash
git -C <path> pull --ff-only
```

This guide deliberately does not name a path. The repository is checked out as
many concurrent worktrees, and this document is shared with teammates whose
directory layouts differ, so any path written here would be wrong for most
readers.

## Path layout

Files are laid out as `<source>/<protocol>/...`:

- `jupyter04/e1/summary.json`
- `jupyter04/e1/seed-42/record.json`

The first segment is a source label rather than the protocol. Results from
different training boxes must not be mixed: Windows runs `torch` built against
cu130 while Linux runs cu128, so the same protocol produces different numbers on
each. Keeping the label as the top-level segment makes overwriting impossible
even when both boxes use the same protocol name.

The label defaults to the short hostname of the publishing machine, which is why
`jupyter04` appears above. Leave it at the default: the periodic publisher and
`run_seeds_parallel.sh`'s end-of-run publish then agree on the prefix without
anyone passing anything, and metrics from one box cannot end up split across two
directories. `--source` overrides it, for the rare case where you deliberately
want a different label.

## Publishing from the training box

Start the periodic publisher alongside training. `--interval` is seconds between
snapshots; `0` publishes once and exits.

```bash
cd ~/zani/ai && setsid nohup uv run python -m zani_ai engagement publish-results \
  --artifacts artifacts/engagement --worktree ~/zani-results \
  --interval 300 > ~/publish.log 2>&1 < /dev/null & echo "PID=$!"
```

`--worktree` must already have `ai/results` checked out; the publisher refuses to
run otherwise, so that it can never stage code or touch files under a run in
progress.

Pushing needs credentials, and the publisher runs unattended, so store them once
instead of letting a prompt block the loop. Use a GitLab Personal Access Token
scoped to the minimum needed to push (`write_repository`), never a password:

```bash
git config --global credential.helper store
```

The first push prompts for your GitLab username and the token and writes them to
`~/.git-credentials`; every later cycle reuses them.

The publisher also commits, so the clone needs a committer identity — a clone
made only for reading code has none, and `git commit` then fails on every cycle:

```bash
git config --global user.email "you@example.com"
git config --global user.name "Your Name"
```

Check on it through its log. A cycle that fails — a flaky network, a rejected
push — prints and retries on the next interval rather than ending the loop:

```bash
tail -f ~/publish.log
```

The timestamp of the newest commit on the branch tells you how far the run has
got, but **it is not a per-interval heartbeat.** A commit appears only when a
metric file's bytes change, and nothing changes between seed completions:
`metrics.json` is written once after the epoch loop ends, and `record.json` and
`summary.json` land at seed boundaries. So a five-seed run produces roughly five
commits, and a gap as long as one seed's runtime is exactly what a healthy run
looks like. Only a gap much longer than a single seed's runtime suggests the run
is gone. Per-epoch progress is printed to the training log, not committed here,
so that is where to look for finer-grained liveness. Check the branch from any
checkout:

```bash
git -C <path> log -1 --format='%cr  %s' ai/results
```

`run_seeds_parallel.sh` publishes once more when the run ends if you give it
`RESULTS_WORKTREE`. The periodic publisher shares the singleuser server's cgroup
and can be culled before the last seed lands, so this makes the final metrics
independent of whether that loop is still alive. When that loop *is* still alive
it holds the lock, and the end-of-run publish says so and exits 0 rather than
reporting a failure: the snapshot is on disk and the loop sends it within one
interval.

```bash
RESULTS_WORKTREE=~/zani-results CUDA_VISIBLE_DEVICES=2 setsid nohup \
  scripts/run_seeds_parallel.sh e1a datasets/processed/engagenet/e1 \
  artifacts/engagement/e1a 42 43 44 45 46 > ~/e1a.log 2>&1 < /dev/null &
```

For the server-side context this runs in — GPU pinning, the idle culler, and how
runs are resumed — see
[`ai-remote-l40s-guide.md`](./ai-remote-l40s-guide.md).

## Rules

- **Metric JSON only.** Checkpoints (`best.pt`), ONNX exports and HTML reports do
  not belong here. They are large binaries, and the branch exists so that metrics
  stay cheap to fetch and diff.
- **Never edit a file by hand.** Every file is generated output; a hand edit makes
  the branch disagree with the run that produced it, and the publisher will
  overwrite it on the next cycle anyway.
- **Never merge `ai/results` into any branch,** and never merge any branch into
  it.
