#!/usr/bin/env bash

set -Eeuo pipefail

readonly SECRET_DIR="/etc/zani/application/secrets"
readonly SECRET_FILE="${SECRET_DIR}/gms_api_key"

if [[ ! -t 0 ]]; then
  echo "Run this script from an interactive terminal so the API key can be entered securely." >&2
  exit 1
fi

if sudo test -e "${SECRET_FILE}"; then
  read -r -p "${SECRET_FILE} already exists. Replace it? [y/N] " replace
  if [[ ! "${replace}" =~ ^[Yy]$ ]]; then
    echo "No changes made."
    exit 0
  fi
fi

read -r -s -p "Paste the GMS API key (input is hidden): " gms_api_key
printf '\n'

if [[ -z "${gms_api_key}" ]]; then
  echo "The GMS API key must not be empty." >&2
  exit 1
fi

if [[ "${gms_api_key}" == *$'\n'* || "${gms_api_key}" == *$'\r'* ]]; then
  echo "The GMS API key must be a single line." >&2
  exit 1
fi

# Pass the key over stdin so it never appears in the command line or shell history.
# The atomic rename exposes either the previous complete key or the new complete key.
printf '%s' "${gms_api_key}" | sudo sh -c '
  set -eu
  secret_dir=/etc/zani/application/secrets
  secret_file=${secret_dir}/gms_api_key

  install -d -o root -g root -m 0750 "${secret_dir}"
  temp_file=$(mktemp "${secret_dir}/.gms_api_key.XXXXXX")
  trap '\''rm -f "${temp_file}"'\'' EXIT HUP INT TERM

  cat >"${temp_file}"
  test -s "${temp_file}"
  chown root:root "${temp_file}"
  chmod 0400 "${temp_file}"
  mv -f "${temp_file}" "${secret_file}"
  trap - EXIT HUP INT TERM
'

unset gms_api_key

if sudo test -s "${SECRET_FILE}"; then
  echo "Installed ${SECRET_FILE} (owner root:root, mode 0400)."
  echo "The backend has not been restarted or redeployed."
else
  echo "Installation failed: ${SECRET_FILE} is missing or empty." >&2
  exit 1
fi
