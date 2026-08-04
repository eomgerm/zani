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

## Required host directories

Track Egress writes recordings to the host; post-class transcription reads them back
through a read-only bind mount. One directory needs a mode the Egress container does
not set by itself:

| Path | Required mode | Owner |
| --- | --- | --- |
| `/srv/zani/recordings` | `0770` | `root:10001` |
| `/srv/zani/recordings/track-egress` | `0771` | `root:root` |

The backend creates each finalization session directory beneath the writable root
with owner `10001:10001`: the session, `manifest`, and `final` directories use
`0750`; `tracks.json` and `lecture.mp4` use `0640`. The same host root is mounted as
`/finalized:rw` for finalization and `/recordings:ro` for media playback. Because that
writable root also contains `track-egress`, Compose overlays the child once more at
`/finalized/track-egress:ro`. Track Egress sources therefore have no writable container
alias: both `/out` and `/finalized/track-egress` are read-only.

Each bit is load-bearing, so do not widen or narrow it:

- **owner and group `rwx`** — the Egress container writes session directories here.
  Removing these stops recording.
- **other `x`** — the backend container runs as UID 10001 and must traverse the mount
  root to open a known file path. Without it every read fails with permission denied.
- **other `r` is deliberately absent** — the backend never lists `/out`. The canonical
  source of file paths is `recording_files.storage_key` in the database, so listing is
  not needed and is not granted.

Parent directories stay `0750 root:root`. They are not widened: Docker resolves the
bind-mount source as root, so the container never traverses `/srv/zani` itself, and
host users remain locked out.

Applying this requires explicit operator approval, the same as the secret files above:

```bash
sudo install -d -o root -g 10001 -m 0770 /srv/zani/recordings
sudo chmod o+x /srv/zani/recordings/track-egress
```

Re-apply it whenever the directory is recreated — a fresh host, a restore, or a
manual `mkdir` all produce `0770` and silently break transcription. **The application
container must not change host permissions at startup**; that would require privileges
the container deliberately drops.

Verify with a throwaway container rather than a host-side `setpriv` check. A host
process running as UID 10001 fails on the `0750` parents regardless of this mode, so
it tests the wrong thing:

```bash
sudo docker run --rm -u 10001:10001 \
  -v /srv/zani/recordings/track-egress:/out:ro \
  --entrypoint sh zani/backend:dev -c \
  'ls /out; head -c 4 /out/<sessionId>/<storageKey>; touch /out/.probe'
```

Expected: listing `/out` fails, reading a known file path succeeds, and the write
fails. Applied and verified on 2026-08-03 (`S15P11A105-95`).

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
