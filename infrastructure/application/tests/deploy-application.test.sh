#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly DEPLOY_SCRIPT="$(cd -- "${SCRIPT_DIR}/.." && pwd)/deploy-application.sh"
readonly TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "${TEST_ROOT}"
}
trap cleanup EXIT

# shellcheck source=../deploy-application.sh
source "${DEPLOY_SCRIPT}"

create_complete_release() {
  local root="$1"
  local relative

  for relative in "${REQUIRED_APPLICATION_RELEASE_FILES[@]}"; do
    mkdir -p "$(dirname -- "${root}/${relative}")"
    printf 'fixture\n' >"${root}/${relative}"
  done
}

complete_release="${TEST_ROOT}/complete"
create_complete_release "${complete_release}"
validate_release_contents "${complete_release}"

incomplete_release="${TEST_ROOT}/incomplete"
create_complete_release "${incomplete_release}"
rm "${incomplete_release}/infrastructure/media/finalize_recording.py"
if (validate_release_contents "${incomplete_release}") 2>"${TEST_ROOT}/incomplete.err"; then
  printf 'Expected an incomplete release to be rejected.\n' >&2
  exit 1
fi
grep -q 'Release is incomplete; missing infrastructure/media/finalize_recording.py' \
  "${TEST_ROOT}/incomplete.err"

workspace="${TEST_ROOT}/workspace"
mkdir -p "${workspace}/infrastructure/application"
cp "${DEPLOY_SCRIPT}" "${workspace}/infrastructure/application/deploy-application.sh"
validate_installed_wrapper "${workspace}" "${DEPLOY_SCRIPT}"

printf '# stale\n' >>"${workspace}/infrastructure/application/deploy-application.sh"
if (validate_installed_wrapper "${workspace}" "${DEPLOY_SCRIPT}") 2>"${TEST_ROOT}/wrapper.err"; then
  printf 'Expected a stale installed wrapper to be rejected.\n' >&2
  exit 1
fi
grep -q 'Installed deployment wrapper is stale' "${TEST_ROOT}/wrapper.err"

# runtime.env 에 적은 값이 컨테이너까지 닿는지 본다.
#
# backend 서비스에 env_file 이 없어 compose.yaml 의 environment 에 나열된 것만 전달된다. 그래서 새 설정을
# 추가할 때 application.yaml 과 runtime.env.example 만 고치고 compose.yaml 을 빠뜨리면, 운영자가 값을 넣어도
# 조용히 무시되고 코드 기본값이 쓰인다 — 껐다고 생각한 기능이 계속 켜져 있는다.
#
# 이 실수가 두 번 났다(S15P11A105-309 리포트 전달 변수, S15P11A105-306 환각 필터). 사람이 기억하는 대신
# 검사로 막는다.
readonly APPLICATION_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"

missing_passthrough=()
while IFS='=' read -r key _; do
  [[ -z "${key}" || "${key}" == \#* ]] && continue
  if ! grep -q "\${${key}" "${APPLICATION_DIR}/compose.yaml"; then
    missing_passthrough+=("${key}")
  fi
done <"${APPLICATION_DIR}/runtime.env.example"

if ((${#missing_passthrough[@]} > 0)); then
  printf 'runtime.env.example keys are not passed through compose.yaml: %s\n' \
    "${missing_passthrough[*]}" >&2
  exit 1
fi

# The silence-hallucination filter previously diverged across application.yaml,
# Compose, and runtime.env.example (S15P11A105-306). Pin both the enable flag and
# threshold in all three deployment contracts so a future edit cannot silently
# change only one layer.
readonly BACKEND_APPLICATION_YAML="${APPLICATION_DIR}/../../backend/src/main/resources/application.yaml"
grep -Fq 'hallucination-filter-enabled: ${POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED:true}' \
  "${BACKEND_APPLICATION_YAML}"
grep -Fq 'no-speech-threshold: ${POSTCLASS_TRANSCRIPTION_NO_SPEECH_THRESHOLD:0.98}' \
  "${BACKEND_APPLICATION_YAML}"
grep -Fq 'POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED: "${POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED:-true}"' \
  "${APPLICATION_DIR}/compose.yaml"
grep -Fq 'POSTCLASS_TRANSCRIPTION_NO_SPEECH_THRESHOLD: "${POSTCLASS_TRANSCRIPTION_NO_SPEECH_THRESHOLD:-0.98}"' \
  "${APPLICATION_DIR}/compose.yaml"
grep -Fxq 'POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED=true' \
  "${APPLICATION_DIR}/runtime.env.example"
grep -Fxq 'POSTCLASS_TRANSCRIPTION_NO_SPEECH_THRESHOLD=0.98' \
  "${APPLICATION_DIR}/runtime.env.example"

printf 'deploy-application tests passed\n'
