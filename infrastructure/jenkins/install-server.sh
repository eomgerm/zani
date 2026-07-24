#!/usr/bin/env bash
set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly AGENT_USER="zani-jenkins-agent"
readonly AGENT_ROOT="/var/lib/zani-jenkins-agent"
readonly CI_ROOT="/var/lib/zani-ci"
readonly CONTROLLER_DATA="/srv/zani/jenkins/controller"
readonly INSTALL_ROOT="/opt/zani/jenkins"
readonly CONTROLLER_ROOT="${INSTALL_ROOT}/controller"
readonly AGENT_INSTALL_ROOT="${INSTALL_ROOT}/agent"
readonly CONTROLLER_SECRETS="/etc/zani/jenkins/secrets"
readonly CONTROLLER_GID="1000"
readonly AGENT_SECRETS="/etc/zani/jenkins/agent"
readonly JENKINS_URL="http://127.0.0.1:18081"
readonly UPDATE_CENTER_URL="https://raw.githubusercontent.com/lework/jenkins-update-center/master/updates/huawei/update-center.json"
readonly MIRROR_PROBE_URL="https://mirrors.huaweicloud.com/jenkins/plugins/git/5.10.1/git.hpi"

die() {
  printf '[zani-jenkins-install] ERROR: %s\n' "$*" >&2
  exit 1
}

require_root() {
  [[ "${EUID}" -eq 0 ]] || die "Run this reviewed installer as root."
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command is missing: $1"
}

require_file() {
  [[ -s "$1" ]] || die "Required non-empty file is missing: $1"
}

wait_for_controller() {
  local attempt
  for attempt in $(seq 1 90); do
    if curl --fail --silent "${JENKINS_URL}/login" >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}

wait_for_job_configs() {
  local attempt
  for attempt in $(seq 1 60); do
    if [[ -s "${CONTROLLER_DATA}/jobs/zani-dev-dispatch/config.xml" ]] &&
       [[ -s "${CONTROLLER_DATA}/jobs/zani-backend-dev/config.xml" ]] &&
       [[ -s "${CONTROLLER_DATA}/jobs/zani-frontend-dev/config.xml" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

verify_update_mirror() {
  curl --fail --location --silent --show-error --max-time 30 \
    --output /dev/null "${UPDATE_CENTER_URL}" || die "The fixed Jenkins update center is unreachable."
  curl --fail --location --silent --show-error --max-time 30 --range 0-0 \
    --output /dev/null "${MIRROR_PROBE_URL}" || die "The fixed Jenkins plugin mirror is unreachable."
}

verify_login_required() {
  local admin_password anonymous_code authenticated_code
  admin_password="$(tr -d '\r\n' <"${CONTROLLER_SECRETS}/JENKINS_ADMIN_PASSWORD")"
  anonymous_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    "${JENKINS_URL}/api/json")"
  authenticated_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --user "zani-admin:${admin_password}" "${JENKINS_URL}/api/json")"
  [[ "${anonymous_code}" == "401" || "${anonymous_code}" == "403" ]] ||
    die "Anonymous Jenkins API access was not rejected (HTTP ${anonymous_code})."
  [[ "${authenticated_code}" == "200" ]] ||
    die "Authenticated Jenkins API access failed (HTTP ${authenticated_code})."
}

verify_required_jobs() {
  local admin_password job response_code
  admin_password="$(tr -d '\r\n' <"${CONTROLLER_SECRETS}/JENKINS_ADMIN_PASSWORD")"
  for job in zani-dev-dispatch zani-backend-dev zani-frontend-dev; do
    response_code="$(curl --silent --output /dev/null --write-out '%{http_code}' \
      --user "zani-admin:${admin_password}" "${JENKINS_URL}/job/${job}/api/json")"
    [[ "${response_code}" == "200" ]] ||
      die "Required Jenkins job is not loaded: ${job} (HTTP ${response_code})."
  done
}

wait_for_agent() {
  local admin_password attempt agent_json
  admin_password="$(tr -d '\r\n' <"${CONTROLLER_SECRETS}/JENKINS_ADMIN_PASSWORD")"
  for attempt in $(seq 1 60); do
    agent_json="$(curl --fail --silent --show-error \
      --user "zani-admin:${admin_password}" \
      "${JENKINS_URL}/computer/zani-backend/api/json" 2>/dev/null || true)"
    if printf '%s' "${agent_json}" | grep -q '"offline"[[:space:]]*:[[:space:]]*false'; then
      return 0
    fi
    sleep 2
  done
  return 1
}

extract_agent_secret() {
  local admin_password jnlp secret
  admin_password="$(tr -d '\r\n' <"${CONTROLLER_SECRETS}/JENKINS_ADMIN_PASSWORD")"
  jnlp="$(curl --fail --silent --show-error \
    --user "zani-admin:${admin_password}" \
    "${JENKINS_URL}/computer/zani-backend/jenkins-agent.jnlp")"
  secret="$(printf '%s' "${jnlp}" | grep -o '<argument>[^<]*</argument>' | head -n 1 | sed 's#</\?argument>##g')"
  [[ "${secret}" =~ ^[0-9a-f]{64}$ ]] || die "Could not retrieve the generated inbound agent secret."
  printf '%s\n' "${secret}" | install -o root -g root -m 0600 /dev/stdin \
    "${AGENT_SECRETS}/JENKINS_AGENT_SECRET"
}

main() {
  require_root
  require_command curl
  require_command docker
  require_command git
  require_command java
  require_command systemctl
  require_command useradd
  require_command usermod
  require_command visudo

  java -version 2>&1 | grep -q 'version "21\.' || die "A Java 21 runtime is required for the agent."
  docker compose version >/dev/null

  require_file "${CONTROLLER_SECRETS}/JENKINS_ADMIN_PASSWORD"
  require_file "${CONTROLLER_SECRETS}/GITLAB_USERNAME"
  require_file "${CONTROLLER_SECRETS}/GITLAB_TOKEN"
  require_file "${CONTROLLER_SECRETS}/GITLAB_WEBHOOK_TOKEN"
  require_file "/etc/zani/application/runtime.env"
  verify_update_mirror

  if ! getent passwd "${AGENT_USER}" >/dev/null; then
    useradd --system --user-group --home-dir "${AGENT_ROOT}" --shell /usr/sbin/nologin "${AGENT_USER}"
  elif [[ "$(getent passwd "${AGENT_USER}" | cut -d: -f6)" != "${AGENT_ROOT}" ]]; then
    usermod --home "${AGENT_ROOT}" "${AGENT_USER}"
  fi

  install -d -o root -g root -m 0755 /etc/zani/jenkins
  install -d -o root -g root -m 0700 "${CONTROLLER_SECRETS}"
  install -d -o root -g root -m 0700 "${AGENT_SECRETS}"
  install -d -o 1000 -g 1000 -m 0750 "${CONTROLLER_DATA}"
  install -d -o "${AGENT_USER}" -g "${AGENT_USER}" -m 0750 "${AGENT_ROOT}"
  install -d -o root -g root -m 0755 \
    "${CONTROLLER_ROOT}" "${AGENT_INSTALL_ROOT}" /opt/zani/deploy \
    "${CI_ROOT}" "${CI_ROOT}/locks" "${CI_ROOT}/tmp" \
    /opt/zani/application /opt/zani/frontend
  install -d -o root -g root -m 0700 "${CI_ROOT}/docker" "${CI_ROOT}/sudo"

  # Docker Compose bind-mounts file-backed secrets without remapping ownership.
  # The host directory remains root-only, while GID 1000 is the Jenkins group
  # inside the pinned controller image and receives read-only access per file.
  chown root:"${CONTROLLER_GID}" \
    "${CONTROLLER_SECRETS}/JENKINS_ADMIN_PASSWORD" \
    "${CONTROLLER_SECRETS}/GITLAB_USERNAME" \
    "${CONTROLLER_SECRETS}/GITLAB_TOKEN" \
    "${CONTROLLER_SECRETS}/GITLAB_WEBHOOK_TOKEN"
  chmod 0640 \
    "${CONTROLLER_SECRETS}/JENKINS_ADMIN_PASSWORD" \
    "${CONTROLLER_SECRETS}/GITLAB_USERNAME" \
    "${CONTROLLER_SECRETS}/GITLAB_TOKEN" \
    "${CONTROLLER_SECRETS}/GITLAB_WEBHOOK_TOKEN"

  install -o root -g root -m 0644 "${SCRIPT_DIR}/compose.yaml" "${CONTROLLER_ROOT}/compose.yaml"
  install -o root -g root -m 0644 "${SCRIPT_DIR}/Dockerfile" "${CONTROLLER_ROOT}/Dockerfile"
  install -o root -g root -m 0644 "${SCRIPT_DIR}/plugins.txt" "${CONTROLLER_ROOT}/plugins.txt"
  install -o root -g root -m 0644 "${SCRIPT_DIR}/jenkins.yaml" "${CONTROLLER_ROOT}/jenkins.yaml"
  install -o root -g root -m 0644 "${SCRIPT_DIR}/jobs.groovy" "${CONTROLLER_ROOT}/jobs.groovy"
  install -o root -g root -m 0755 "${SCRIPT_DIR}/../application/deploy-application.sh" \
    /opt/zani/deploy/deploy-application
  install -o root -g root -m 0755 "${SCRIPT_DIR}/../frontend/deploy-frontend-jenkins.sh" \
    /opt/zani/deploy/deploy-frontend

  visudo -cf "${SCRIPT_DIR}/sudoers-zani-jenkins-agent" >/dev/null
  install -o root -g root -m 0440 "${SCRIPT_DIR}/sudoers-zani-jenkins-agent" \
    /etc/sudoers.d/zani-jenkins-agent
  install -o root -g root -m 0644 "${SCRIPT_DIR}/systemd/zani-jenkins-agent.service" \
    /etc/systemd/system/zani-jenkins-agent.service

  docker compose -f "${CONTROLLER_ROOT}/compose.yaml" build --pull controller
  docker compose -f "${CONTROLLER_ROOT}/compose.yaml" up -d --force-recreate controller
  wait_for_controller || die "Jenkins controller did not become ready."
  verify_login_required
  wait_for_job_configs || die "Required Jenkins job configuration files were not created."

  # Job DSL creates new job configs during the first JCasC boot. Restart once
  # so a fresh controller loads those new items into the Jenkins model.
  docker compose -f "${CONTROLLER_ROOT}/compose.yaml" restart controller
  wait_for_controller || die "Jenkins controller did not become ready after loading new jobs."
  verify_login_required
  verify_required_jobs

  curl --fail --silent --show-error "${JENKINS_URL}/jnlpJars/agent.jar" \
    -o "${AGENT_INSTALL_ROOT}/agent.jar"
  chown root:root "${AGENT_INSTALL_ROOT}/agent.jar"
  chmod 0644 "${AGENT_INSTALL_ROOT}/agent.jar"
  extract_agent_secret

  systemctl daemon-reload
  systemctl enable zani-jenkins-agent.service
  systemctl restart zani-jenkins-agent.service
  systemctl is-active --quiet zani-jenkins-agent.service || die "Jenkins agent service is not active."
  [[ "$(systemctl show zani-jenkins-agent.service --property=NoNewPrivileges --value)" == "no" ]] ||
    die "Jenkins agent cannot invoke the reviewed sudo deployment wrappers."
  wait_for_agent || die "Jenkins agent did not reconnect to the controller."

  printf '[zani-jenkins-install] Controller and agent are active. UI: %s\n' "${JENKINS_URL}"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
