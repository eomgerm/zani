#!/usr/bin/env bash
set -Eeuo pipefail

readonly APPLICATION_ROOT="${APPLICATION_ROOT:-/opt/zani/application}"
readonly RELEASES_DIR="${APPLICATION_ROOT}/releases"
readonly CURRENT_LINK="${APPLICATION_ROOT}/current"
readonly RUNTIME_ENV="${RUNTIME_ENV:-/etc/zani/application/runtime.env}"
readonly AGENT_ROOT="${JENKINS_AGENT_ROOT:-/var/lib/zani-jenkins-agent}"
readonly CI_ROOT="/var/lib/zani-ci"
readonly CI_LOCKS="${CI_ROOT}/locks"
readonly CI_TMP="${CI_ROOT}/tmp"
readonly CI_DOCKER="${CI_ROOT}/docker"
readonly DEPLOY_LOCK="${CI_LOCKS}/zani-application-deploy.lock"
readonly VERIFY_LOCK="${CI_LOCKS}/zani-application-verify.lock"
readonly BACKEND_CONTAINER="zani-backend"
readonly CI_JDK_IMAGE="eclipse-temurin:21.0.11_10-jdk-jammy"
readonly CI_MYSQL_IMAGE="mysql:8.4.10"
readonly CI_REDIS_IMAGE="redis@sha256:b1addbe72465a718643cff9e60a58e6df1841e29d6d7d60c9a85d8d72f08d1a7"
readonly -a APPLICATION_RELEASE_PATHS=(
  ".dockerignore"
  "backend"
  "infrastructure/application"
  "infrastructure/media"
)
readonly -a REQUIRED_APPLICATION_RELEASE_FILES=(
  ".dockerignore"
  "backend/Dockerfile"
  "infrastructure/application/compose.yaml"
  "infrastructure/application/deploy-application.sh"
  "infrastructure/media/finalize-recording.sh"
  "infrastructure/media/finalize_recording.py"
)

log() {
  printf '[zani-deploy] %s\n' "$*"
}

die() {
  printf '[zani-deploy] ERROR: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage:
  deploy-application verify <jenkins-workspace> <40-character-git-sha>
  deploy-application deploy <jenkins-workspace> <40-character-git-sha>
  deploy-application rollback [application-<12-character-git-sha>]
  deploy-application status
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

prepare_recording_directories() {
  local root="/srv/zani/recordings"
  local track_egress="${root}/track-egress"

  # /finalized:rw와 /recordings:ro가 공유하는 호스트 정본. 앱 컨테이너 UID/GID 10001만 쓰고 읽는다.
  install -d -o root -g 10001 -m 0770 "${root}"
  [[ -d "${track_egress}" ]] || die "Track Egress directory is missing: ${track_egress}"
  # Egress 쓰기(owner/group rwx)는 유지하고 앱 컨테이너에는 경로 통과(x)만 허용한다. 목록 읽기(r)는 주지 않는다.
  chown root:root "${track_egress}"
  chmod 0771 "${track_egress}"
  [[ "$(stat -c '%u:%g:%a' "${root}")" == "0:10001:770" ]] ||
    die "Recording root permissions are invalid: ${root}"
  [[ "$(stat -c '%u:%g:%a' "${track_egress}")" == "0:0:771" ]] ||
    die "Track Egress permissions are invalid: ${track_egress}"
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

validate_installed_wrapper() {
  local workspace="$1"
  local installed_wrapper="$2"
  local repository_wrapper="${workspace}/infrastructure/application/deploy-application.sh"

  [[ -f "${repository_wrapper}" ]] ||
    die "Deployment wrapper is missing from the requested checkout: ${repository_wrapper}"
  [[ -f "${installed_wrapper}" ]] || die "Installed deployment wrapper is missing: ${installed_wrapper}"
  cmp -s "${installed_wrapper}" "${repository_wrapper}" ||
    die "Installed deployment wrapper is stale. Review and install ${repository_wrapper} at /opt/zani/deploy/deploy-application before retrying."
}

validate_release_contents() {
  local release_dir="$1"
  local required_path

  for required_path in "${REQUIRED_APPLICATION_RELEASE_FILES[@]}"; do
    [[ -f "${release_dir}/${required_path}" ]] ||
      die "Release is incomplete; missing ${required_path}: ${release_dir}. If this is not the current release, quarantine it before retrying the same SHA."
  done
}

wait_for_container_command() {
  local container="$1"
  shift
  local attempt

  for attempt in $(seq 1 60); do
    if docker exec "${container}" "$@" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  return 1
}

verify_backend() {
  local requested_workspace="$1"
  local sha="$2"
  local workspace short_sha temp_dir archive network mysql redis

  validate_sha "${sha}"
  workspace="$(resolve_workspace "${requested_workspace}")"
  validate_checkout "${workspace}" "${sha}"

  exec 8>"${VERIFY_LOCK}"
  flock -n 8 || die "Another backend verification is already running."

  short_sha="${sha:0:12}"
  temp_dir="$(mktemp -d "${CI_TMP}/zani-ci-${short_sha}.XXXXXX")"
  archive="${temp_dir}/backend.tar"
  network="zani-ci-${short_sha}"
  mysql="zani-ci-mysql-${short_sha}"
  redis="zani-ci-redis-${short_sha}"

  VERIFY_TEMP_DIR="${temp_dir}"
  VERIFY_NETWORK="${network}"
  VERIFY_MYSQL="${mysql}"
  VERIFY_REDIS="${redis}"

  cleanup_verify() {
    docker rm -f "${VERIFY_MYSQL}" "${VERIFY_REDIS}" >/dev/null 2>&1 || true
    docker network rm "${VERIFY_NETWORK}" >/dev/null 2>&1 || true
    rm -rf -- "${VERIFY_TEMP_DIR}"
  }
  trap cleanup_verify EXIT

  archive_commit "${workspace}" "${sha}" "${archive}" backend
  mkdir -p "${temp_dir}/source"
  tar -xf "${archive}" -C "${temp_dir}/source"

  docker network create "${network}" >/dev/null
  docker run -d --name "${mysql}" --network "${network}" --network-alias mysql \
    -e MYSQL_DATABASE=zani \
    -e MYSQL_ROOT_PASSWORD=zani-ci-root \
    "${CI_MYSQL_IMAGE}" >/dev/null
  docker run -d --name "${redis}" --network "${network}" --network-alias redis \
    "${CI_REDIS_IMAGE}" redis-server --save '' --appendonly no >/dev/null

  wait_for_container_command "${mysql}" mysqladmin ping -h 127.0.0.1 -u root -pzani-ci-root --silent ||
    die "CI MySQL did not become ready."
  wait_for_container_command "${redis}" redis-cli ping || die "CI Redis did not become ready."

  log "Running Spotless, JUnit, and bootJar for ${short_sha} with isolated CI dependencies."
  docker run --rm --network "${network}" \
    -v "${temp_dir}/source/backend:/workspace" \
    -w /workspace \
    -e LOCAL_DB_URL='jdbc:mysql://mysql:3306/zani?serverTimezone=Asia/Seoul&characterEncoding=UTF-8' \
    -e LOCAL_DB_USERNAME=root \
    -e LOCAL_DB_PASSWORD=zani-ci-root \
    -e LOCAL_REDIS_HOST=redis \
    -e LOCAL_REDIS_PORT=6379 \
    -e GOOGLE_OAUTH_CLIENT_ID=zani-ci.apps.googleusercontent.com \
    "${CI_JDK_IMAGE}" \
    bash ./gradlew clean spotlessCheck test bootJar --no-daemon

  log "Backend verification succeeded for ${sha}."
}

load_runtime_environment() {
  [[ -r "${RUNTIME_ENV}" ]] || die "Runtime environment file is not readable: ${RUNTIME_ENV}"
  # The file is root-owned and limited to simple KEY=VALUE entries by the installer.
  set -a
  # shellcheck disable=SC1090
  source "${RUNTIME_ENV}"
  set +a
  [[ -n "${FRONTEND_ORIGIN:-}" ]] || die "FRONTEND_ORIGIN is missing from ${RUNTIME_ENV}."
  [[ -n "${GOOGLE_OAUTH_CLIENT_ID:-}" ]] ||
    die "GOOGLE_OAUTH_CLIENT_ID is missing from ${RUNTIME_ENV}."
}

compose_for_release() {
  local release_dir="$1"
  shift
  docker compose \
    --project-directory "${release_dir}/infrastructure/application" \
    -f "${release_dir}/infrastructure/application/compose.yaml" \
    "$@"
}

container_image() {
  docker inspect --format '{{.Config.Image}}' "${BACKEND_CONTAINER}" 2>/dev/null || true
}

wait_for_backend() {
  local attempt health
  for attempt in $(seq 1 60); do
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${BACKEND_CONTAINER}" 2>/dev/null || true)"
    if [[ "${health}" == "healthy" ]] && curl --fail --silent --show-error \
      http://127.0.0.1:18080/actuator/health >/dev/null; then
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

  BACKEND_IMAGE="${image}" compose_for_release "${release_dir}" \
    up -d --no-deps --no-build --force-recreate backend
  wait_for_backend
}

restore_previous_backend() {
  local previous_release="$1"
  local previous_image="$2"

  [[ -n "${previous_release}" && -d "${previous_release}" && -n "${previous_image}" ]] || return 1
  log "Restoring previous backend image ${previous_image}."
  activate_release "${previous_release}" "${previous_image}"
}

deploy_backend() {
  local requested_workspace="$1"
  local sha="$2"
  local workspace short_sha release_name release_dir staging_dir archive
  local image previous_release previous_image deployed_at

  validate_sha "${sha}"
  workspace="$(resolve_workspace "${requested_workspace}")"
  validate_checkout "${workspace}" "${sha}"
  validate_installed_wrapper "${workspace}" "$(realpath -e "${BASH_SOURCE[0]}")"
  load_runtime_environment

  exec 9>"${DEPLOY_LOCK}"
  flock -n 9 || die "Another application deployment or rollback is already running."

  prepare_recording_directories

  short_sha="${sha:0:12}"
  release_name="application-${short_sha}"
  release_dir="${RELEASES_DIR}/${release_name}"
  staging_dir="${RELEASES_DIR}/.staging-${release_name}-$$"
  archive="${staging_dir}.tar"
  image="zani/backend:git-${short_sha}"
  previous_release="$(realpath -e "${CURRENT_LINK}" 2>/dev/null || true)"
  previous_image="$(container_image)"
  deployed_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  # The first managed deployment may follow the manually installed dev release.
  # Add only wrapper metadata so that this already-created release is also rollbackable.
  if [[ -n "${previous_release}" && -d "${previous_release}" && -n "${previous_image}" && ! -e "${previous_release}/.zani-release" ]]; then
    cat >"${previous_release}/.zani-release" <<EOF
GIT_SHA=legacy
BACKEND_IMAGE=${previous_image}
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
    validate_release_contents "${release_dir}"
  else
    mkdir -p "${staging_dir}"
    archive_commit "${workspace}" "${sha}" "${archive}" "${APPLICATION_RELEASE_PATHS[@]}"
    tar -xf "${archive}" -C "${staging_dir}"
    rm -f -- "${archive}"
    validate_release_contents "${staging_dir}"
    cat >"${staging_dir}/.zani-release" <<EOF
GIT_SHA=${sha}
BACKEND_IMAGE=${image}
DEPLOYED_AT=${deployed_at}
PREVIOUS_RELEASE=${previous_release}
EOF
    chown -R root:root "${staging_dir}"
    find "${staging_dir}" -type d -exec chmod 0755 {} +
    find "${staging_dir}" -type f -exec chmod 0644 {} +
    chmod 0755 "${staging_dir}/infrastructure/application/deploy-application.sh"
    mv "${staging_dir}" "${release_dir}"
  fi

  log "Validating Compose and building ${image}."
  BACKEND_IMAGE="${image}" compose_for_release "${release_dir}" config --quiet
  BACKEND_IMAGE="${image}" compose_for_release "${release_dir}" build backend

  log "Recreating only ${BACKEND_CONTAINER}; MySQL, Redis, Nginx, and media services remain untouched."
  if ! activate_release "${release_dir}" "${image}"; then
    log "Deployment health check failed."
    if restore_previous_backend "${previous_release}" "${previous_image}"; then
      die "Deployment failed and the previous backend was restored."
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

  [[ "${requested}" =~ ^application-([0-9a-f]{12}|dev-[0-9TZ]+)$ ]] || die "Invalid release name: ${requested}"
  [[ -d "${RELEASES_DIR}/${requested}" ]] || die "Release does not exist: ${requested}"
  [[ -r "${RELEASES_DIR}/${requested}/.zani-release" ]] || die "Release metadata is missing."
  printf '%s\n' "${RELEASES_DIR}/${requested}"
}

rollback_backend() {
  local target_release current_release current_image target_image

  require_command docker
  load_runtime_environment
  exec 9>"${DEPLOY_LOCK}"
  flock -n 9 || die "Another application deployment or rollback is already running."

  target_release="$(resolve_rollback_target "${1:-}")"
  target_image="$(metadata_value "${target_release}" BACKEND_IMAGE)"
  [[ "${target_image}" =~ ^zani/backend:(git-[0-9a-f]{12}|dev|rollback-[A-Za-z0-9._-]+)$ ]] ||
    die "Rollback image metadata is invalid."
  docker image inspect "${target_image}" >/dev/null 2>&1 || die "Rollback image is not present: ${target_image}"

  current_release="$(realpath -e "${CURRENT_LINK}" 2>/dev/null || true)"
  current_image="$(container_image)"
  log "Rolling back backend to $(basename "${target_release}")."
  if ! activate_release "${target_release}" "${target_image}"; then
    log "Rollback health check failed; attempting to restore the current backend."
    restore_previous_backend "${current_release}" "${current_image}" || true
    die "Rollback failed."
  fi
  ln -sfn "${target_release}" "${CURRENT_LINK}"
  log "Rollback succeeded: $(basename "${target_release}")."
}

show_status() {
  local current image health
  current="$(realpath -e "${CURRENT_LINK}" 2>/dev/null || true)"
  image="$(container_image)"
  health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${BACKEND_CONTAINER}" 2>/dev/null || printf 'missing')"
  printf 'current_release=%s\nbackend_image=%s\nbackend_health=%s\n' \
    "${current:-missing}" "${image:-missing}" "${health}"
}

main() {
  require_root
  require_command docker
  require_command cmp
  require_command flock
  require_command git
  require_command realpath
  require_command stat
  require_command install
  require_command tar
  require_ci_directory "${CI_DOCKER}" 700
  require_ci_directory "${CI_LOCKS}" 755
  require_ci_directory "${CI_TMP}" 755
  export DOCKER_CONFIG="${CI_DOCKER}"

  case "${1:-}" in
    verify)
      [[ "$#" -eq 3 ]] || { usage; exit 2; }
      verify_backend "$2" "$3"
      ;;
    deploy)
      [[ "$#" -eq 3 ]] || { usage; exit 2; }
      deploy_backend "$2" "$3"
      ;;
    rollback)
      [[ "$#" -le 2 ]] || { usage; exit 2; }
      rollback_backend "${2:-}"
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

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
