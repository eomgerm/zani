#!/usr/bin/env bash
#
# ZANI 강의 녹화 후처리 병합 CLI (S15P11A105-176)
#
# manifest 를 입력받아 finalize_recording.py 로 최종 lecture.mp4 를 생성한다.
# 세션별 flock 으로 중복 실행을 막고, 검증에 성공한 .partial 만 atomic rename 한다.
# 트리거·DB orchestration 은 이 스크립트를 호출하는 상위 작업(S15P11A105-68) 범위다.
#
# 사용법:
#   finalize-recording.sh --manifest <path> --output <path> [--session-dir <dir>]
#
# 종료 코드: finalize_recording.py 의 코드를 그대로 전달한다.
#   0 성공 / 2 manifest 오류 / 3 필수 영상 없음 / 4 입력 검증 실패
#   5 FFmpeg 실패 / 6 최종 검증 실패 / 9 락 획득 실패 / 64 사용법 오류

set -euo pipefail

readonly EXIT_USAGE=64
readonly EXIT_LOCK=9

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
python_bin=${PYTHON_BIN:-python3}

manifest=""
output=""
session_dir=""

usage() {
  sed -n '3,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest) manifest=${2:-}; shift 2 ;;
    --output) output=${2:-}; shift 2 ;;
    --session-dir) session_dir=${2:-}; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit "$EXIT_USAGE" ;;
  esac
done

if [[ -z "$manifest" || -z "$output" ]]; then
  echo "error: --manifest and --output are required" >&2
  usage >&2
  exit "$EXIT_USAGE"
fi
if [[ ! -f "$manifest" ]]; then
  echo "error: manifest not found: $manifest" >&2
  exit "$EXIT_USAGE"
fi

lock_root=${session_dir:-$(dirname "$manifest")}
lock_file="$lock_root/.finalize.lock"

# 세션별 flock (비차단). 이미 처리 중이면 중복 병합하지 않는다.
exec 9>"$lock_file"
if ! flock -n 9; then
  echo "error: another finalize-recording is already running for $lock_root" >&2
  exit "$EXIT_LOCK"
fi

# 병합 실행. 검증에 성공하면 <output>.partial 이 남는다.
set +e
if [[ -n "$session_dir" ]]; then
  "$python_bin" "$script_dir/finalize_recording.py" \
    --manifest "$manifest" --output "$output" --session-dir "$session_dir"
else
  "$python_bin" "$script_dir/finalize_recording.py" \
    --manifest "$manifest" --output "$output"
fi
rc=$?
set -e

if [[ "$rc" -ne 0 ]]; then
  echo "error: finalize_recording.py failed (exit $rc)" >&2
  exit "$rc"
fi

# 검증 성공한 partial 만 동일 파일시스템에서 atomic rename 한다.
mv -f -- "$output.partial" "$output"
echo "finalized: $output" >&2
