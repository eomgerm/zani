#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly DEPLOY="$(cd -- "${SCRIPT_DIR}/.." && pwd)/deploy-controller.sh"
readonly TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "${TEST_ROOT}"
}
trap cleanup EXIT

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

assert_contains() {
  local output="$1" needle="$2"
  grep -qF -- "${needle}" <<<"${output}" || fail "expected to find ${needle} in:
${output}"
}

# Non-empty token and username files for cases that must pass the credential checks.
readonly TOKEN_OK="${TEST_ROOT}/token"
printf 'dummy-token\n' >"${TOKEN_OK}"
readonly USER_OK="${TEST_ROOT}/user"
printf 'gitlab+deploy-token-1\n' >"${USER_OK}"

# An empty controller root the dry run must never touch.
readonly CTRL="${TEST_ROOT}/controller"
mkdir -p "${CTRL}"

# 1. Missing token file exits non-zero.
if TOKEN_FILE="${TEST_ROOT}/nope" USER_FILE="${USER_OK}" CONTROLLER_ROOT="${CTRL}" "${DEPLOY}" --dry-run >/dev/null 2>&1; then
  fail "missing token should exit non-zero"
fi

# 1b. Missing username file exits non-zero.
if TOKEN_FILE="${TOKEN_OK}" USER_FILE="${TEST_ROOT}/nope" CONTROLLER_ROOT="${CTRL}" "${DEPLOY}" --dry-run >/dev/null 2>&1; then
  fail "missing username should exit non-zero"
fi

# 2. Unknown flag exits non-zero.
if TOKEN_FILE="${TOKEN_OK}" CONTROLLER_ROOT="${CTRL}" "${DEPLOY}" --bogus >/dev/null 2>&1; then
  fail "unknown flag should exit non-zero"
fi

# 3. Dry run: exits 0, prints intended actions, and touches nothing.
output="$(TOKEN_FILE="${TOKEN_OK}" USER_FILE="${USER_OK}" CONTROLLER_ROOT="${CTRL}" "${DEPLOY}" --dry-run)"
assert_contains "${output}" "DRY-RUN"
assert_contains "${output}" "${CTRL}"
assert_contains "${output}" "compose.yaml"
assert_contains "${output}" "up -d --force-recreate"
if [ -n "$(ls -A "${CTRL}")" ]; then
  fail "dry run must not write into controller root"
fi

# 4. Dry run without --build must not mention a build step; with --build it must.
if grep -qF -- "build --pull" <<<"${output}"; then
  fail "default dry run should not include a build step"
fi
build_output="$(TOKEN_FILE="${TOKEN_OK}" USER_FILE="${USER_OK}" CONTROLLER_ROOT="${CTRL}" "${DEPLOY}" --dry-run --build)"
assert_contains "${build_output}" "build --pull"

printf 'deploy-controller tests passed\n'
