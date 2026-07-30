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
# LiveKit 토큰 서명과 Egress 제어에 쓴다. 없으면 강의방 입장과 녹화가 모두 503으로 막힌다.
LIVEKIT_API_KEY="$(read_secret /run/secrets/livekit_api_key)"
LIVEKIT_API_SECRET="$(read_secret /run/secrets/livekit_api_secret)"
export DB_PASSWORD REDIS_PASSWORD JWT_SECRET LIVEKIT_API_KEY LIVEKIT_API_SECRET

exec gosu zani:zani java -jar /app/app.jar
