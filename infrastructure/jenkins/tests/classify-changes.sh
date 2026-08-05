#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly CLASSIFIER="$(cd -- "${SCRIPT_DIR}/.." && pwd)/classify-changes.sh"
readonly TEST_ROOT="$(mktemp -d)"

cleanup() {
  rm -rf -- "${TEST_ROOT}"
}
trap cleanup EXIT

assert_line() {
  local output="$1"
  local expected="$2"
  grep -qxF "${expected}" <<<"${output}" || {
    printf 'Expected line %q in output:\n%s\n' "${expected}" "${output}" >&2
    exit 1
  }
}

commit_file() {
  local path="$1"
  local content="$2"
  mkdir -p "$(dirname -- "${path}")"
  printf '%s\n' "${content}" >"${path}"
  git add -- "${path}"
  git commit -q -m "test change"
  git rev-parse HEAD
}

cd "${TEST_ROOT}"
git init -q
git config user.name "ZANI CI Test"
git config user.email "ci-test@example.invalid"

base_sha="$(commit_file README.md base)"

frontend_sha="$(commit_file fe/src/app.tsx frontend)"
output="$("${CLASSIFIER}" "${base_sha}" "${frontend_sha}")"
assert_line "${output}" "backend=false"
assert_line "${output}" "frontend=true"

backend_sha="$(commit_file backend/src/App.java backend)"
output="$("${CLASSIFIER}" "${frontend_sha}" "${backend_sha}")"
assert_line "${output}" "backend=true"
assert_line "${output}" "frontend=false"

media_sha="$(commit_file infrastructure/media/finalize_recording.py media)"
output="$("${CLASSIFIER}" "${backend_sha}" "${media_sha}")"
assert_line "${output}" "backend=true"
assert_line "${output}" "frontend=false"

dockerignore_sha="$(commit_file .dockerignore dockerignore)"
output="$("${CLASSIFIER}" "${media_sha}" "${dockerignore_sha}")"
assert_line "${output}" "backend=true"
assert_line "${output}" "frontend=false"

media_docs_sha="$(commit_file infrastructure/media/finalize-recording-design.md media-docs)"
output="$("${CLASSIFIER}" "${dockerignore_sha}" "${media_docs_sha}")"
assert_line "${output}" "backend=false"
assert_line "${output}" "frontend=false"

mkdir -p fe infrastructure/application
printf 'both\n' >fe/both.txt
printf 'both\n' >infrastructure/application/both.yaml
git add fe/both.txt infrastructure/application/both.yaml
git commit -q -m "both components"
both_sha="$(git rev-parse HEAD)"
output="$("${CLASSIFIER}" "${media_docs_sha}" "${both_sha}")"
assert_line "${output}" "backend=true"
assert_line "${output}" "frontend=true"

unrelated_sha="$(commit_file docs/notes.md docs)"
output="$("${CLASSIFIER}" "${both_sha}" "${unrelated_sha}")"
assert_line "${output}" "backend=false"
assert_line "${output}" "frontend=false"

fallback_sha="$(commit_file infrastructure/frontend/compose.yaml frontend-fallback)"
output="$("${CLASSIFIER}" "${ZERO_SHA:-0000000000000000000000000000000000000000}" "${fallback_sha}")"
assert_line "${output}" "before=${unrelated_sha}"
assert_line "${output}" "backend=false"
assert_line "${output}" "frontend=true"

printf 'classify-changes tests passed\n'
