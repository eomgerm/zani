#!/usr/bin/env bash

set -Eeuo pipefail

readonly SECRET_DIR="/etc/zani/application/secrets"
readonly SECRET_FILE="${SECRET_DIR}/smtp_password"

if [[ ! -t 0 ]]; then
  echo "Run this script from an interactive terminal so the SMTP password can be entered securely." >&2
  exit 1
fi

if sudo test -e "${SECRET_FILE}"; then
  read -r -p "${SECRET_FILE} already exists. Replace it? [y/N] " replace
  if [[ ! "${replace}" =~ ^[Yy]$ ]]; then
    echo "No changes made."
    exit 0
  fi
fi

read -r -s -p "Paste the SMTP app password (input is hidden): " smtp_password
printf '\n'

if [[ -z "${smtp_password}" ]]; then
  echo "The SMTP app password must not be empty." >&2
  exit 1
fi

if [[ "${smtp_password}" == *$'\n'* || "${smtp_password}" == *$'\r'* ]]; then
  echo "The SMTP app password must be a single line." >&2
  exit 1
fi

printf '%s' "${smtp_password}" | sudo sh -c '
  set -eu
  secret_dir=/etc/zani/application/secrets
  secret_file=${secret_dir}/smtp_password

  install -d -o root -g root -m 0750 "${secret_dir}"
  temp_file=$(mktemp "${secret_dir}/.smtp_password.XXXXXX")
  trap '\''rm -f "${temp_file}"'\'' EXIT HUP INT TERM

  cat >"${temp_file}"
  test -s "${temp_file}"
  chown root:root "${temp_file}"
  chmod 0400 "${temp_file}"
  mv -f "${temp_file}" "${secret_file}"
  trap - EXIT HUP INT TERM
'

unset smtp_password

if sudo test -s "${SECRET_FILE}"; then
  echo "Installed ${SECRET_FILE} (owner root:root, mode 0400)."
  echo "The backend has not been restarted or redeployed."
else
  echo "Installation failed: ${SECRET_FILE} is missing or empty." >&2
  exit 1
fi
