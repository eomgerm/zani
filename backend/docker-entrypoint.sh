#!/bin/sh
set -eu

read_secret() {
  secret_path="$1"
  if [ ! -r "$secret_path" ]; then
    echo "Required secret is not readable: $secret_path" >&2
    exit 1
  fi
  tr -d '\r\n' < "$secret_path"
}

DB_PASSWORD="$(read_secret /run/secrets/mysql_app_password)"
REDIS_PASSWORD="$(read_secret /run/secrets/redis_app_password)"
JWT_SECRET="$(read_secret /run/secrets/jwt_secret)"
export DB_PASSWORD REDIS_PASSWORD JWT_SECRET

exec gosu zani:zani java -jar /app/app.jar
