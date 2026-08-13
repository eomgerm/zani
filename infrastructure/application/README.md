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
- `smtp_password` (required before deploying this Compose revision)

Do not commit secret values to Git. Creating the server-side directory and applying restrictive file permissions requires explicit operator approval.

Install or rotate the GMS key from an interactive EC2 shell. The script reads the
key without echoing it, never places it in the command line, and atomically writes
the root-only secret file:

```bash
bash infrastructure/application/install-gms-api-key.sh
```

Install or rotate the SMTP app password the same way. The password is mounted as
a Docker secret and exported to Spring only when `NOTIFICATION_EMAIL_ENABLED=true`:

```bash
bash infrastructure/application/install-smtp-password.sh
```

The root-owned `/etc/zani/application/runtime.env` file supplies non-secret runtime configuration. Use `runtime.env.example` as the field-name reference, but enter the actual Google Web Client ID only on the server:

```dotenv
FRONTEND_ORIGIN=https://i15a105.p.ssafy.io
GOOGLE_OAUTH_CLIENT_ID=example.apps.googleusercontent.com
GMS_MOCK_ENABLED=false
COACHING_TRIGGER_COOLDOWN=PT10M
COACHING_TRIGGER_THRESHOLD=0.30
ATTENTION_TIMELINE_MINIMUM_ELIGIBLE=5
ATTENTION_TIMELINE_FOCUS_COVERAGE_FLOOR=0.7
ATTENTION_TIMELINE_REQUIRED_CONNECTION=PT1M
RECORDING_MEDIA_URL_TEMPLATE=https://i15a105.p.ssafy.io/api/v1/sessions/{sessionId}/media
RECORDING_THUMBNAIL_URL_TEMPLATE=https://i15a105.p.ssafy.io/api/v1/sessions/{sessionId}/thumbnail
POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED=true
POSTCLASS_TRANSCRIPTION_NO_SPEECH_THRESHOLD=0.98
POSTCLASS_TRANSCRIPTION_REPEATED_PHRASE_FILTER_ENABLED=true
NOTIFICATION_EMAIL_ENABLED=false
NOTIFICATION_EMAIL_FROM=example@gmail.com
NOTIFICATION_APP_BASE_URL=https://i15a105.p.ssafy.io
SPRING_MAIL_HOST=smtp.gmail.com
SPRING_MAIL_PORT=587
SPRING_MAIL_USERNAME=example@gmail.com
SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true
```

`GOOGLE_OAUTH_CLIENT_ID` is the Google Web Client ID used to validate the ID-token audience. It is an identifier, not a client secret. The same value is injected into the frontend build as `NEXT_PUBLIC_GOOGLE_CLIENT_ID` by the frontend Compose configuration.

`GMS_MOCK_ENABLED` must remain `false` for deployed integration tests. To repeat
coaching-tip scenarios quickly, temporarily set `COACHING_TRIGGER_COOLDOWN=PT1M`.
The default `COACHING_TRIGGER_THRESHOLD=0.30` already triggers for one flagged
student in a one-to-three-student test; lower it only when the intended scenario
needs one flagged student out of a larger denominator. Restore the cooldown to
`PT10M` after testing.

The three `ATTENTION_TIMELINE_*` values are the gates that decide whether a report
timeline shows a group value at all (S15P11A105-315 exposed them; the remaining
thresholds in `attention.timeline` are still image-only). A bucket is hidden when
fewer than `ATTENTION_TIMELINE_MINIMUM_ELIGIBLE` students are eligible, when the
four-level judgements cover less than `ATTENTION_TIMELINE_FOCUS_COVERAGE_FLOOR` of
the bucket, or — for a given student — when that student has not been continuously
connected for `ATTENTION_TIMELINE_REQUIRED_CONNECTION`. A demo session that never
reaches five participants therefore renders as an empty graph rather than as a
report with gaps. Lower `ATTENTION_TIMELINE_MINIMUM_ELIGIBLE` only for such
sessions and never to `1`, which makes one student's value the group average and
removes the anonymity the gate exists for (NFR-SEC-007 · REPORT-I-002 ·
REPORT-I-005); restore `5` afterwards. `ATTENTION_TIMELINE_REQUIRED_CONNECTION`
must keep matching the real-time path, or the same moment is judged differently in
the live view and in the report. Environment changes reach the backend only when
the container is recreated, so deploy a release — `docker restart zani-backend`
keeps the old values.

`POSTCLASS_TRANSCRIPTION_REPEATED_PHRASE_FILTER_ENABLED` is the primary defence
against Whisper's silence hallucinations (S15P11A105-316). It drops a run of three
or more consecutive identical short phrases (20 characters or fewer after
normalisation) when most of the run also looks silent; otherwise it keeps the
first occurrence. Because it reads the text rather than a probability, it does not
confuse hallucinations with real speech: across five real lectures it removed
nothing, and on the observed hallucination tracks it removed every one. It is on
by default and can be switched off here.

`POSTCLASS_TRANSCRIPTION_HALLUCINATION_FILTER_ENABLED` drops segments whose
`no_speech_prob` reaches `POSTCLASS_TRANSCRIPTION_NO_SPEECH_THRESHOLD`. **It is on
by default at `0.98` as a tail-cleanup rule.** It shipped on at `0.8` and had to be reverted: on a real session it
deleted four of the instructor's ten segments — 63 seconds of the core explanation
and the closing summary. `no_speech_prob` belongs to the 30-second decoding window,
not to the segment, so real speech inside a mostly quiet window inherits a high
value while a hallucination beside real speech inherits a low one. The observed
distributions interleave (real speech at `0.515 · 0.698 · 0.745 · 0.811 · 0.864 ·
0.921 · 0.964`, hallucinations at `0.622 · 0.790 · 0.895 · 0.906 · 0.924 · 0.953 ·
0.965`), so no threshold separates the full distributions. In the stored 32-track
corpus, the highest real-speech value was `0.921`, while all nine segments at or
above `0.98` were hallucinations. A separate GMS response contained a real-speech
continuation at `0.964`; it remains directly because it is below the threshold.
The adjacency rescue is a separate safeguard for a continuation that does reach
the threshold. The `0.98` rule therefore catches only the extremes of fully silent
stretches that the repeated-phrase rule misses. Set the flag to `false` for
immediate rollback. The threshold must stay within `0.0`~`1.0`; the backend refuses
to start otherwise.

Neither filter touches the GMS response or the chunk checkpoints, so changing
either setting and re-assembling produces a different stored transcript without
calling GMS again.

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

`deploy-application.sh` applies and verifies this approved policy on every deployment
before Compose recreates the application containers. Operators do not need to repeat
the commands manually during a normal deployment. For a fresh host or an out-of-band
directory restore, the equivalent preparation is:

```bash
sudo install -d -o root -g 10001 -m 0770 /srv/zani/recordings
sudo chmod o+x /srv/zani/recordings/track-egress
```

The deployment script also repairs a manually recreated directory before startup.
**The application container itself does not change host permissions**; the privileged
host deployment step owns that responsibility so the container can keep its dropped
capabilities.

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

Run the deployment-wrapper regression test without changing the host:

```bash
./infrastructure/application/tests/deploy-application.test.sh
```

1. `docker compose config`
2. Build the backend image.
3. Start MySQL and Application Redis and wait for healthy status.
4. Start the backend and wait for `/actuator/health` to report healthy.
5. Add the Nginx API locations and enable HTTP/2 on the TLS listener (`listen 443 ssl http2;`,
   or `http2 on;` on nginx >= 1.25.1). Over HTTP/1.1 the my-lectures thumbnails queue behind the
   browser's per-host connection cap; the header of `nginx-locations.conf` carries the rationale
   and the `curl` verification. Run `nginx -t` before reload.
6. Verify HTTPS API, Vercel CORS, LiveKit signaling, media, Egress, Gerrit, and SSH regression checks.

## Immutable deployment and rollback

Jenkins uses `deploy-application.sh` through a root-owned copy at `/opt/zani/deploy/deploy-application`. The wrapper validates the exact clean Git SHA, tests the backend with disposable MySQL and Redis containers, creates an immutable release and image, recreates only `zani-backend`, and switches `current` only after the health checks pass.

The root-owned copy is an intentional privilege boundary and is not updated by a
Git merge. Before a commit that changes `deploy-application.sh` can deploy, an
operator must review and install the exact tracked file from that checkout:

```bash
sudo install -o root -g root -m 0755 \
  /path/to/reviewed/checkout/infrastructure/application/deploy-application.sh \
  /opt/zani/deploy/deploy-application
```

The wrapper compares itself with the requested checkout before changing the host.
A mismatch fails with `Installed deployment wrapper is stale`; do not bypass this
check by copying files into a running container.

Application releases contain the tracked root `.dockerignore`, `backend/`,
`infrastructure/application/`, and `infrastructure/media/`. The wrapper verifies
the Compose file, Dockerfile, and recording-finalization worker files before the
Docker build starts. An incomplete immutable release is never silently reused.

If a build failed after creating `application-<sha>` and the same SHA must be
retried, first prove that it is not the active release, then quarantine it outside
the releases directory:

```bash
sudo /opt/zani/deploy/deploy-application status
failed_release="application-0123456789ab"
current_release="$(readlink -f /opt/zani/application/current)"
candidate="/opt/zani/application/releases/${failed_release}"
test "${candidate}" != "${current_release}"
sudo install -d -o root -g root -m 0755 /opt/zani/application/failed-releases
sudo mv -- "${candidate}" \
  "/opt/zani/application/failed-releases/${failed_release}-failed"
```

Never move the path printed as `current_release`. Prefer a new commit SHA when the
failed release does not need to be retried.

The wrapper never changes Nginx, UFW, SSH, MySQL volumes, Application Redis volumes, or media services. Automatic release deletion is intentionally disabled. See `../jenkins/README.md` for the complete CI/CD boundary and rollback procedure.
