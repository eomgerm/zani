#!/usr/bin/env bash
set -Eeuo pipefail

public_origin="${1:-https://i15a105.p.ssafy.io}"

docker_cli=(docker)
if ! docker info >/dev/null 2>&1; then
  if command -v sudo >/dev/null 2>&1 && sudo -n docker info >/dev/null 2>&1; then
    docker_cli=(sudo docker)
  else
    echo "현재 사용자 또는 비밀번호 없는 sudo로 Docker에 접근할 수 없습니다." >&2
    exit 1
  fi
fi

check_url() {
  local label="$1"
  local url="$2"
  local expected_codes="$3"
  local actual_code

  actual_code="$(curl --silent --show-error --location --max-time 10 --output /dev/null --write-out '%{http_code}' "${url}")"
  if [[ ! ",${expected_codes}," == *",${actual_code},"* ]]; then
    echo "FAIL ${label}: ${url} -> HTTP ${actual_code} (expected ${expected_codes})" >&2
    return 1
  fi
  echo "PASS ${label}: HTTP ${actual_code}"
}

health_status="$("${docker_cli[@]}" inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' zani-frontend 2>/dev/null || true)"
if [[ "${health_status}" != "healthy" ]]; then
  echo "FAIL container health: ${health_status:-not-found}" >&2
  exit 1
fi
echo "PASS container health: healthy"

check_url "frontend loopback" "http://127.0.0.1:13000/" "200,304"
check_url "frontend HTTPS" "${public_origin}/" "200,304"
check_url "Nginx health" "${public_origin}/healthz" "200,304"
check_url "Spring API docs" "${public_origin}/v3/api-docs" "200,401,403"

echo "프론트엔드와 기존 HTTPS 경로 smoke 검증이 완료되었습니다."
