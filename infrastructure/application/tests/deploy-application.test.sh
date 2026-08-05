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

printf 'deploy-application tests passed\n'
