#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd -- "${script_dir}/../.." && pwd)"
compose_file="${script_dir}/compose.yaml"

for command_name in docker curl; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    echo "필수 명령을 찾을 수 없습니다: ${command_name}" >&2
    exit 1
  fi
done

docker_cli=(docker)
if ! docker info >/dev/null 2>&1; then
  if command -v sudo >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
    docker_cli=(sudo docker)
  else
    echo "현재 사용자 또는 비밀번호 없는 sudo로 Docker에 접근할 수 없습니다." >&2
    exit 1
  fi
fi

docker_run() {
  "${docker_cli[@]}" "$@"
}

compose_run() {
  if [[ "${docker_cli[0]}" == "sudo" ]]; then
    sudo env \
      "FRONTEND_IMAGE=${FRONTEND_IMAGE}" \
      "NEXT_PUBLIC_API_BASE_URL=${NEXT_PUBLIC_API_BASE_URL}" \
      docker compose "$@"
  else
    docker compose "$@"
  fi
}

if ! docker_run compose version >/dev/null 2>&1; then
  echo "Docker Compose v2가 필요합니다." >&2
  exit 1
fi

release_sha=""
if command -v git >/dev/null 2>&1 && git -C "${repo_root}" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  release_sha="$(git -C "${repo_root}" rev-parse --verify HEAD)"

  if [[ -n "$(git -C "${repo_root}" status --porcelain -- fe infrastructure/frontend)" ]]; then
    echo "fe 또는 infrastructure/frontend에 커밋되지 않은 변경이 있습니다." >&2
    exit 1
  fi
else
  release_sha="${RELEASE_SHA:-}"
fi

if [[ ! "${release_sha}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Git 작업 트리가 아니면 RELEASE_SHA에 40자리 Git SHA를 지정해야 합니다." >&2
  exit 1
fi

export FRONTEND_IMAGE="${FRONTEND_IMAGE:-zani/frontend:${release_sha}}"
export NEXT_PUBLIC_API_BASE_URL="${NEXT_PUBLIC_API_BASE_URL:-}"

previous_image="$(docker_run inspect --format '{{.Config.Image}}' zani-frontend 2>/dev/null || true)"

echo "프론트엔드 이미지 빌드: ${FRONTEND_IMAGE}"
compose_run --file "${compose_file}" build frontend
compose_run --file "${compose_file}" up --detach --no-build --no-deps frontend

for attempt in {1..18}; do
  health_status="$(docker_run inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' zani-frontend 2>/dev/null || true)"
  if [[ "${health_status}" == "healthy" ]]; then
    curl --fail --silent --show-error --max-time 5 http://127.0.0.1:13000/ >/dev/null
    echo "프론트엔드 배포 완료: ${FRONTEND_IMAGE}"
    exit 0
  fi

  if [[ "${health_status}" == "unhealthy" ]]; then
    break
  fi

  sleep 5
done

echo "프론트엔드 health check 실패" >&2
compose_run --file "${compose_file}" logs --tail 100 frontend >&2 || true

if [[ -n "${previous_image}" && "${previous_image}" != "${FRONTEND_IMAGE}" ]]; then
  echo "이전 이미지로 롤백합니다: ${previous_image}" >&2
  FRONTEND_IMAGE="${previous_image}" compose_run --file "${compose_file}" up --detach --no-build --no-deps frontend
fi

exit 1
