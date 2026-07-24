#!/usr/bin/env bash
set -Eeuo pipefail

readonly ZERO_SHA="0000000000000000000000000000000000000000"

die() {
  printf '[zani-dispatch] ERROR: %s\n' "$*" >&2
  exit 1
}

is_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]]
}

readonly REQUESTED_BEFORE="${1:-}"
readonly AFTER_SHA="${2:-}"

is_sha "${AFTER_SHA}" || die "after SHA must be exactly 40 lowercase hexadecimal characters"
git cat-file -e "${AFTER_SHA}^{commit}" 2>/dev/null || die "after commit is not available: ${AFTER_SHA}"

before_sha=""
if is_sha "${REQUESTED_BEFORE}" && [[ "${REQUESTED_BEFORE}" != "${ZERO_SHA}" ]] && \
   git cat-file -e "${REQUESTED_BEFORE}^{commit}" 2>/dev/null && \
   git merge-base --is-ancestor "${REQUESTED_BEFORE}" "${AFTER_SHA}"; then
  before_sha="${REQUESTED_BEFORE}"
elif git rev-parse --verify "${AFTER_SHA}^" >/dev/null 2>&1; then
  before_sha="$(git rev-parse "${AFTER_SHA}^")"
fi

backend=false
frontend=false

classify_path() {
  case "$1" in
    backend/*|infrastructure/application/*)
      backend=true
      ;;
    fe/*|infrastructure/frontend/*)
      frontend=true
      ;;
  esac
}

if [[ -n "${before_sha}" ]]; then
  while IFS= read -r -d '' changed_path; do
    classify_path "${changed_path}"
  done < <(git diff --name-only --diff-filter=ACDMRTUXB -z "${before_sha}" "${AFTER_SHA}")
else
  while IFS= read -r -d '' changed_path; do
    classify_path "${changed_path}"
  done < <(git diff-tree --root --no-commit-id --name-only --diff-filter=ACDMRTUXB -r -z "${AFTER_SHA}")
fi

printf 'before=%s\nafter=%s\nbackend=%s\nfrontend=%s\n' \
  "${before_sha:-root}" "${AFTER_SHA}" "${backend}" "${frontend}"
