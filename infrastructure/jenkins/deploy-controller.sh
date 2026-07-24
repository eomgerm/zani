#!/usr/bin/env bash
# Bring the Jenkins controller config to the latest committed dev state and
# recreate the controller container. Manual, operator-run (sudo). It only
# re-applies the controller config files and recreates the controller; it never
# touches the OS user, systemd, sudoers, secret values, or nginx (those belong
# to install-server.sh). Jira: S15P11A105-190.
set -Eeuo pipefail

# Overridable for testing; defaults target the live EC2 controller.
CONTROLLER_ROOT="${CONTROLLER_ROOT:-/opt/zani/jenkins/controller}"
TOKEN_FILE="${TOKEN_FILE:-/etc/zani/jenkins/secrets/GITLAB_TOKEN}"
USER_FILE="${USER_FILE:-/etc/zani/jenkins/secrets/GITLAB_USERNAME}"
REPO_URL="${REPO_URL:-https://lab.ssafy.com/s15-webmobile1-sub1/S15P11A105.git}"
BRANCH="${BRANCH:-dev}"
JENKINS_URL="${JENKINS_URL:-http://127.0.0.1:18081}"
GIT_BIN="${GIT_BIN:-git}"
DOCKER_BIN="${DOCKER_BIN:-docker}"
CURL_BIN="${CURL_BIN:-curl}"
HEALTH_ATTEMPTS="${HEALTH_ATTEMPTS:-30}"
HEALTH_DELAY="${HEALTH_DELAY:-5}"

readonly CONTROLLER_FILES=(compose.yaml jenkins.yaml jobs.groovy Dockerfile plugins.txt)

BUILD=false
DRY_RUN=false

usage() {
  cat <<'EOF'
Usage: deploy-controller.sh [--build] [--dry-run]
  --build     Rebuild the controller image first (only when Dockerfile or plugins.txt changed)
  --dry-run   Print the planned actions without cloning, copying, or recreating
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --build) BUILD=true ;;
    --dry-run) DRY_RUN=true ;;
    -h|--help) usage; exit 0 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
  shift
done

log() { printf '%s\n' "$*"; }
die() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

[ -s "${TOKEN_FILE}" ] || die "GitLab token file missing or empty: ${TOKEN_FILE}"
[ -s "${USER_FILE}" ] || die "GitLab username file missing or empty: ${USER_FILE}"

compose_up="${DOCKER_BIN} compose -f ${CONTROLLER_ROOT}/compose.yaml up -d --force-recreate controller"
compose_build="${DOCKER_BIN} compose -f ${CONTROLLER_ROOT}/compose.yaml build --pull controller"

if [ "${DRY_RUN}" = true ]; then
  log "DRY-RUN: clone ${REPO_URL} (branch ${BRANCH}, --depth 1) into a temp dir"
  log "DRY-RUN: copy to ${CONTROLLER_ROOT}: ${CONTROLLER_FILES[*]}"
  [ "${BUILD}" = true ] && log "DRY-RUN: ${compose_build}"
  log "DRY-RUN: ${compose_up}"
  log "DRY-RUN: poll ${JENKINS_URL}/login until healthy (${HEALTH_ATTEMPTS} attempts)"
  exit 0
fi

# Authenticate the clone through a temporary GIT_ASKPASS helper so the token is
# never written into the remote URL or the log. The GitLab username comes from
# the URL (a deploy token requires its own username, not "oauth2"), so git only
# asks the helper for the password.
askpass="$(mktemp)"
workdir="$(mktemp -d)"
cleanup() { rm -f -- "${askpass}"; rm -rf -- "${workdir}"; }
trap cleanup EXIT
printf '#!/bin/sh\ncat -- %q\n' "${TOKEN_FILE}" >"${askpass}"
chmod +x "${askpass}"

gitlab_user="$(tr -d '\r\n' < "${USER_FILE}")"
log "Cloning ${BRANCH} (shallow)..."
GIT_ASKPASS="${askpass}" GIT_TERMINAL_PROMPT=0 "${GIT_BIN}" clone --depth 1 --branch "${BRANCH}" \
  "https://${gitlab_user}@${REPO_URL#https://}" "${workdir}/repo"

src="${workdir}/repo/infrastructure/jenkins"
[ -d "${src}" ] || die "infrastructure/jenkins not found in cloned ${BRANCH}"

log "Applying controller config to ${CONTROLLER_ROOT}..."
for f in "${CONTROLLER_FILES[@]}"; do
  install -m 0644 -o root -g root "${src}/${f}" "${CONTROLLER_ROOT}/${f}"
done

if [ "${BUILD}" = true ]; then
  log "Building controller image..."
  # shellcheck disable=SC2086
  ${compose_build}
fi

log "Recreating controller..."
# shellcheck disable=SC2086
${compose_up}

log "Waiting for controller health at ${JENKINS_URL}/login..."
attempt=1
while [ "${attempt}" -le "${HEALTH_ATTEMPTS}" ]; do
  if "${CURL_BIN}" -fsS "${JENKINS_URL}/login" >/dev/null 2>&1; then
    sha="$("${GIT_BIN}" -C "${workdir}/repo" rev-parse HEAD)"
    log "Controller healthy. Deployed ${BRANCH} @ ${sha}"
    exit 0
  fi
  sleep "${HEALTH_DELAY}"
  attempt=$((attempt + 1))
done
die "controller did not become healthy after ${HEALTH_ATTEMPTS} attempts (check: ${DOCKER_BIN} compose -f ${CONTROLLER_ROOT}/compose.yaml logs)"
