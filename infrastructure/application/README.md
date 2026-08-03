# Application Stack

This stack runs the `dev` branch backend on the existing single EC2 host. The frontend is deployed as a separate container on the same host.

## Network layout

- Host Nginx owns public ports 80 and 443.
- Backend is published only on `127.0.0.1:18080`.
- MySQL and Application Redis are reachable only inside `zani-application-internal`.
- Media Redis remains a separate service and data store.
- No additional UFW rule is required for this stack.
- Nginx routes backend API and Swagger paths to this stack and serves the separately deployed frontend at `/`.

## Required secret files

The Compose file reads these files from `/etc/zani/application/secrets`:

- `mysql_root_password`
- `mysql_app_password`
- `redis_app_password`
- `jwt_secret`
- `livekit_api_key`
- `livekit_api_secret`
- `gms_api_key`

Do not commit secret values to Git. Creating the server-side directory and applying restrictive file permissions requires explicit operator approval.

Install or rotate the GMS key from an interactive EC2 shell. The script reads the
key without echoing it, never places it in the command line, and atomically writes
the root-only secret file:

```bash
bash infrastructure/application/install-gms-api-key.sh
```

The root-owned `/etc/zani/application/runtime.env` file supplies non-secret runtime configuration. Use `runtime.env.example` as the field-name reference, but enter the actual Google Web Client ID only on the server:

```dotenv
FRONTEND_ORIGIN=https://i15a105.p.ssafy.io
GOOGLE_OAUTH_CLIENT_ID=example.apps.googleusercontent.com
GMS_MOCK_ENABLED=false
COACHING_TRIGGER_COOLDOWN=PT10M
COACHING_TRIGGER_THRESHOLD=0.30
```

`GOOGLE_OAUTH_CLIENT_ID` is the Google Web Client ID used to validate the ID-token audience. It is an identifier, not a client secret. The same value is injected into the frontend build as `NEXT_PUBLIC_GOOGLE_CLIENT_ID` by the frontend Compose configuration.

`GMS_MOCK_ENABLED` must remain `false` for deployed integration tests. To repeat
coaching-tip scenarios quickly, temporarily set `COACHING_TRIGGER_COOLDOWN=PT1M`.
The default `COACHING_TRIGGER_THRESHOLD=0.30` already triggers for one flagged
student in a one-to-three-student test; lower it only when the intended scenario
needs one flagged student out of a larger denominator. Restore the cooldown to
`PT10M` after testing.

## Schema policy

The stack preserves the `dev` profile's `spring.jpa.hibernate.ddl-auto=validate` policy. The current `dev` branch has no migration tool or schema migration files, so migrations must be added before deploying persistent entities that require database tables.

## Verification order

1. `docker compose config`
2. Build the backend image.
3. Start MySQL and Application Redis and wait for healthy status.
4. Start the backend and wait for `/actuator/health` to report healthy.
5. Add the Nginx API locations and run `nginx -t` before reload.
6. Verify HTTPS API, Vercel CORS, LiveKit signaling, media, Egress, Gerrit, and SSH regression checks.

## Immutable deployment and rollback

Jenkins uses `deploy-application.sh` through a root-owned copy at `/opt/zani/deploy/deploy-application`. The wrapper validates the exact clean Git SHA, tests the backend with disposable MySQL and Redis containers, creates an immutable release and image, recreates only `zani-backend`, and switches `current` only after the health checks pass.

The wrapper never changes Nginx, UFW, SSH, MySQL volumes, Application Redis volumes, or media services. Automatic release deletion is intentionally disabled. See `../jenkins/README.md` for the complete CI/CD boundary and rollback procedure.
