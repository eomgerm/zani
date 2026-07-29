#!/usr/bin/env bash
set -Eeuo pipefail

readonly FRONTEND_ROOT="${FRONTEND_ROOT:-/opt/zani/frontend}"
readonly RELEASES_DIR="${FRONTEND_ROOT}/releases"
readonly CURRENT_LINK="${FRONTEND_ROOT}/current"
readonly AGENT_ROOT="${JENKINS_AGENT_ROOT:-/var/lib/zani-jenkins-agent}"
readonly CI_ROOT="/var/lib/zani-ci"
readonly CI_LOCKS="${CI_ROOT}/locks"
readonly CI_TMP="${CI_ROOT}/tmp"
readonly CI_DOCKER="${CI_ROOT}/docker"
readonly DEPLOY_LOCK="${CI_LOCKS}/zani-frontend-deploy.lock"
readonly VERIFY_LOCK="${CI_LOCKS}/zani-frontend-verify.lock"
readonly FRONTEND_CONTAINER="zani-frontend"
readonly CI_NODE_IMAGE="node:22-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2"

log() {
  printf '[zani-frontend-deploy] %s\n' "$*"
}

die() {
  printf '[zani-frontend-deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage:
  deploy-frontend verify <jenkins-workspace> <40-character-git-sha>
  deploy-frontend deploy <jenkins-workspace> <40-character-git-sha>
  deploy-frontend rollback [frontend-<12-character-git-sha>]
  deploy-frontend status
EOF
}

require_root() {
  [[ "${EUID}" -eq 0 ]] || die "This command must run as root through the approved sudo rule."
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command is missing: $1"
}

require_ci_directory() {
  local directory="$1"
  local mode="$2"
  [[ -d "${directory}" ]] || die "Required CI directory is missing: ${directory}"
  [[ "$(stat -c '%U:%G:%a' "${directory}")" == "root:root:${mode}" ]] ||
    die "CI directory must be owned by root:root with mode ${mode}: ${directory}"
}

validate_sha() {
  [[ "$1" =~ ^[0-9a-f]{40}$ ]] || die "Git SHA must be exactly 40 lowercase hexadecimal characters."
}

resolve_workspace() {
  local requested="$1"
  local resolved_agent_root resolved_workspace

  resolved_agent_root="$(realpath -e "${AGENT_ROOT}")"
  resolved_workspace="$(realpath -e "${requested}")"
  case "${resolved_workspace}" in
    "${resolved_agent_root}"/*) ;;
    *) die "Workspace must be below ${resolved_agent_root}." ;;
  esac

  [[ -d "${resolved_workspace}/.git" ]] || die "Workspace is not a Git checkout."
  printf '%s\n' "${resolved_workspace}"
}

validate_checkout() {
  local workspace="$1"
  local expected_sha="$2"
  local actual_sha

  actual_sha="$(git -c safe.directory="${workspace}" -C "${workspace}" rev-parse HEAD)"
  [[ "${actual_sha}" == "${expected_sha}" ]] || die "Workspace HEAD does not match the requested SHA."
  git -c safe.directory="${workspace}" -C "${workspace}" diff --quiet -- ||
    die "Workspace has unstaged tracked changes."
  git -c safe.directory="${workspace}" -C "${workspace}" diff --cached --quiet -- ||
    die "Workspace has staged changes."
}

archive_commit() {
  local workspace="$1"
  local sha="$2"
  local destination="$3"
  shift 3

  git -c safe.directory="${workspace}" -C "${workspace}" archive \
    --format=tar --output="${destination}" "${sha}" "$@"
}

verify_frontend() (
  local requested_workspace="$1"
  local sha="$2"
  local workspace short_sha temp_dir archive

  validate_sha "${sha}"
  workspace="$(resolve_workspace "${requested_workspace}")"
  validate_checkout "${workspace}" "${sha}"

  exec 8>"${VERIFY_LOCK}"
  flock -n 8 || die "Another frontend verification is already running."

  short_sha="${sha:0:12}"
  temp_dir="$(mktemp -d "${CI_TMP}/zani-frontend-ci-${short_sha}.XXXXXX")"
  archive="${temp_dir}/frontend.tar"
  trap 'rm -rf -- "${temp_dir}"' EXIT

  archive_commit "${workspace}" "${sha}" "${archive}" fe
  mkdir -p "${temp_dir}/source"
  tar -xf "${archive}" -C "${temp_dir}/source"
  chown -R 1000:1000 "${temp_dir}/source/fe"

  log "Running npm ci, lint, tests, and production build for ${short_sha}."
  docker run --rm \
    --cpus 1.5 \
    --memory 2g \
    --pids-limit 512 \
    --user 1000:1000 \
    --read-only \
    --tmpfs /tmp:rw,noexec,nosuid,size=512m,uid=1000,gid=1000,mode=1777 \
    --security-opt no-new-privileges:true \
    --cap-drop ALL \
    -e HOME=/tmp \
    -e NEXT_TELEMETRY_DISABLED=1 \
    -e NEXT_PUBLIC_API_BASE_URL= \
    -v "${temp_dir}/source/fe:/workspace:rw" \
    -w /workspace \
    "${CI_NODE_IMAGE}" \
    sh -lc 'npm ci && npm run lint && npm run test && npm run build'

  log "Frontend verification succeeded for ${sha}."
)

compose_for_release() {
  local release_dir="$1"
  local image="$2"
  shift 2

  FRONTEND_IMAGE="${image}" NEXT_PUBLIC_API_BASE_URL="" docker compose \
    --project-directory "${release_dir}/infrastructure/frontend" \
    -f "${release_dir}/infrastructure/frontend/compose.yaml" \
    "$@"
}

container_image() {
  docker inspect --format '{{.Config.Image}}' "${FRONTEND_CONTAINER}" 2>/dev/null || true
}

wait_for_frontend() {
  local attempt health
  for attempt in $(seq 1 60); do
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${FRONTEND_CONTAINER}" 2>/dev/null || true)"
    if [[ "${health}" == "healthy" ]] && curl --fail --silent --show-error \
      http://127.0.0.1:13000/ >/dev/null; then
      return 0
    fi
    [[ "${health}" == "unhealthy" ]] && return 1
    sleep 2
  done
  return 1
}

metadata_value() {
  local release_dir="$1"
  local key="$2"
  sed -n "s/^${key}=//p" "${release_dir}/.zani-release" | head -n 1
}

activate_release() {
  local release_dir="$1"
  local image="$2"

  compose_for_release "${release_dir}" "${image}" \
    up -d --no-deps --no-build --force-recreate frontend
  wait_for_frontend
}

restore_previous_frontend() {
  local previous_release="$1"
  local previous_image="$2"

  [[ -n "${previous_release}" && -d "${previous_release}" && -n "${previous_image}" ]] || return 1
  log "Restoring previous frontend image ${previous_image}."
  activate_release "${previous_release}" "${previous_image}"
}

deploy_frontend() {
  local requested_workspace="$1"
  local sha="$2"
  local workspace short_sha release_name release_dir staging_dir archive
  local image previous_release previous_image deployed_at

  validate_sha "${sha}"
  workspace="$(resolve_workspace "${requested_workspace}")"
  validate_checkout "${workspace}" "${sha}"

  exec 9>"${DEPLOY_LOCK}"
  flock -n 9 || die "Another frontend deployment or rollback is already running."

  short_sha="${sha:0:12}"
  release_name="frontend-${short_sha}"
  release_dir="${RELEASES_DIR}/${release_name}"
  staging_dir="${RELEASES_DIR}/.staging-${release_name}-$$"
  archive="${staging_dir}.tar"
  image="zani/frontend:git-${short_sha}"
  previous_release="$(realpath -e "${CURRENT_LINK}" 2>/dev/null || true)"
  previous_image="$(container_image)"
  deployed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  if [[ -n "${previous_release}" && -d "${previous_release}" && -n "${previous_image}" && ! -e "${previous_release}/.zani-release" ]]; then
    cat >"${previous_release}/.zani-release" <<EOF
GIT_SHA=legacy
FRONTEND_IMAGE=${previous_image}
DEPLOYED_AT=unknown
PREVIOUS_RELEASE=
EOF
    chmod 0644 "${previous_release}/.zani-release"
  fi

  mkdir -p "${RELEASES_DIR}"
  if [[ -d "${release_dir}" ]]; then
    [[ -r "${release_dir}/.zani-release" ]] || die "Existing release has no metadata: ${release_dir}"
    [[ "$(metadata_value "${release_dir}" GIT_SHA)" == "${sha}" ]] ||
      die "Existing release metadata does not match ${sha}."
  else
    mkdir -p "${staging_dir}"
    archive_commit "${workspace}" "${sha}" "${archive}" fe infrastructure/frontend .gitattributes
    tar -xf "${archive}" -C "${staging_dir}"
    rm -f -- "${archive}"
    cat >"${staging_dir}/.zani-release" <<EOF
GIT_SHA=${sha}
FRONTEND_IMAGE=${image}
DEPLOYED_AT=${deployed_at}
PREVIOUS_RELEASE=${previous_release}
EOF
    chown -R root:root "${staging_dir}"
    find "${staging_dir}" -type d -exec chmod 0755 {} +
    find "${staging_dir}" -type f -exec chmod 0644 {} +
    chmod 0755 "${staging_dir}/infrastructure/frontend/"*.sh
    mv "${staging_dir}" "${release_dir}"
  fi

  log "Validating Compose and building ${image}."
  compose_for_release "${release_dir}" "${image}" config --quiet
  compose_for_release "${release_dir}" "${image}" build frontend

  log "Recreating only ${FRONTEND_CONTAINER}; backend, Nginx, Jenkins, and media services remain untouched."
  if ! activate_release "${release_dir}" "${image}"; then
    log "Deployment health check failed."
    if restore_previous_frontend "${previous_release}" "${previous_image}"; then
      die "Deployment failed and the previous frontend was restored."
    fi
    die "Deployment failed and automatic restoration was not possible."
  fi

  ln -sfn "${release_dir}" "${CURRENT_LINK}"
  log "Deployment succeeded: ${release_name} (${sha})."
}

resolve_rollback_target() {
  local requested="${1:-}"
  local current previous

  current="$(realpath -e "${CURRENT_LINK}" 2>/dev/null || true)"
  if [[ -z "${requested}" ]]; then
    [[ -n "${current}" && -r "${current}/.zani-release" ]] ||
      die "The current release has no rollback metadata; specify a release name."
    previous="$(metadata_value "${current}" PREVIOUS_RELEASE)"
    [[ -n "${previous}" ]] || die "No previous release is recorded."
    requested="$(basename "${previous}")"
  fi

  [[ "${requested}" =~ ^(frontend-[0-9a-f]{12}|[0-9a-f]{40})$ ]] || die "Invalid release name: ${requested}"
  [[ -d "${RELEASES_DIR}/${requested}" ]] || die "Release does not exist: ${requested}"
  [[ -r "${RELEASES_DIR}/${requested}/.zani-release" ]] || die "Release metadata is missing."
  printf '%s\n' "${RELEASES_DIR}/${requested}"
}

rollback_frontend() {
  local target_release current_release current_image target_image

  exec 9>"${DEPLOY_LOCK}"
  flock -n 9 || die "Another frontend deployment or rollback is already running."

  target_release="$(resolve_rollback_target "${1:-}")"
  target_image="$(metadata_value "${target_release}" FRONTEND_IMAGE)"
  [[ "${target_image}" =~ ^zani/frontend:(git-[0-9a-f]{12}|[0-9a-f]{40}|dev)$ ]] ||
    die "Rollback image metadata is invalid."
  docker image inspect "${target_image}" >/dev/null 2>&1 || die "Rollback image is not present: ${target_image}"

  current_release="$(realpath -e "${CURRENT_LINK}" 2>/dev/null || true)"
  current_image="$(container_image)"
  log "Rolling back frontend to $(basename "${target_release}")."
  if ! activate_release "${target_release}" "${target_image}"; then
    log "Rollback health check failed; attempting to restore the current frontend."
    restore_previous_frontend "${current_release}" "${current_image}" || true
    die "Rollback failed."
  fi
  ln -sfn "${target_release}" "${CURRENT_LINK}"
  log "Rollback succeeded: $(basename "${target_release}")."
}

show_status() {
  local current image health
  current="$(realpath -e "${CURRENT_LINK}" 2>/dev/null || true)"
  image="$(container_image)"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${FRONTEND_CONTAINER}" 2>/dev/null || printf 'missing')"
  printf 'current_release=%s\nfrontend_image=%s\nfrontend_health=%s\n' \
    "${current:-missing}" "${image:-missing}" "${health}"
}

main() {
  require_root
  require_command curl
  require_command docker
  require_command flock
  require_command git
  require_command realpath
  require_command stat
  require_command tar
  require_ci_directory "${CI_DOCKER}" 700
  require_ci_directory "${CI_LOCKS}" 755
  require_ci_directory "${CI_TMP}" 755
  export DOCKER_CONFIG="${CI_DOCKER}"

  case "${1:-}" in
    verify)
      [[ "$#" -eq 3 ]] || { usage; exit 2; }
      verify_frontend "$2" "$3"
      ;;
    deploy)
      [[ "$#" -eq 3 ]] || { usage; exit 2; }
      deploy_frontend "$2" "$3"
      ;;
    rollback)
      [[ "$#" -le 2 ]] || { usage; exit 2; }
      rollback_frontend "${2:-}"
      ;;
    status)
      [[ "$#" -eq 1 ]] || { usage; exit 2; }
      show_status
      ;;
    *)
      usage
      exit 2
      ;;
  esac
}

main "$@"
