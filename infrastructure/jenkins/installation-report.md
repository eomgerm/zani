# ZANI Jenkins installation report

## Installation result

The production Jenkins controller and its dedicated inbound agent were installed on the SSAFY EC2 host on 2026-07-22. A project-scoped GitLab Deploy Token with repository read permission is loaded, read-only access to the `dev` branch is verified, and the project webhook is registered. Webhook delivery and the first controlled deployment remain pending until this Jenkins configuration is reviewed, merged, and available on `dev`.

| Item | Result |
| --- | --- |
| Jenkins controller | Running and healthy |
| Jenkins version | 2.568.1, JDK 21 image pinned by digest |
| Host agent Java | OpenJDK 21.0.11 |
| Controller executors | 0 |
| Dedicated agent | Online, 1 executor |
| Agent transport | Inbound WebSocket over loopback |
| Authentication | Required |
| Anonymous API | HTTP 403 |
| Authenticated API | HTTP 200 |
| GitLab push trigger | Installed, `dev` branch only |
| GitLab clone credential | Project Deploy Token loaded; `dev` read verified |
| GitLab project webhook | Registered for `dev` push events; delivery test pending |

## Network exposure

- Jenkins binds to `127.0.0.1:18081`; port 18081 is not reachable externally.
- Nginx continues to own public HTTPS port 443.
- The only public Jenkins route is `POST /project/zani-backend-dev`.
- `/login` and `/job/*` remain handled by the existing Nginx 404 route.
- No UFW rule or public port was added for Jenkins.
- coturn remains on 8443 and is independent of this route.

External route checks after the Nginx reload:

| Request | Expected result | Verified result |
| --- | --- | --- |
| `GET /healthz` | Existing health response | 200 |
| `GET /login` | Jenkins UI not exposed | 404 |
| `GET /job/zani-backend-dev` | Jenkins job UI not exposed | 404 |
| `GET /project/zani-backend-dev` | Method rejected | 403 |
| Webhook POST with wrong secret | Authentication rejected | 401 |

The previous Nginx configuration is preserved at:

```text
/etc/nginx/sites-available/zani.before-jenkins-webhook.20260722T065502Z.backup
```

## Installed paths and identities

```text
/srv/zani/jenkins/controller          Jenkins persistent controller data
/var/lib/zani-jenkins-agent           Dedicated agent state and workspaces
/opt/zani/jenkins/controller          Reviewed controller configuration
/opt/zani/jenkins/agent/agent.jar     Pinned controller-provided agent binary
/opt/zani/deploy/deploy-application   Root-owned deployment boundary
/etc/zani/jenkins/secrets             Controller secret source directory
/etc/zani/jenkins/agent               Root-only inbound-agent secret source
/etc/systemd/system/zani-jenkins-agent.service
/etc/sudoers.d/zani-jenkins-agent
```

The `zani-jenkins-agent` account uses `/usr/sbin/nologin`. Its sudo rule allows only the reviewed deployment wrapper's `verify` and `deploy` commands. It cannot invoke a general root shell through this rule.

## Secret boundary

- Controller secret directory: `root:root 0700`.
- File-backed controller secrets: `root:1000 0640`; numeric GID 1000 is the Jenkins group inside the pinned image. The root-only host parent path prevents ordinary host traversal.
- Inbound-agent secret source: `root:root 0600`.
- systemd `LoadCredential` exposes an ephemeral read-only copy only to the running agent service.
- Secret values were not written to this report or command output.

## Installation corrections

Two permission mismatches were found during the first persistent boot and corrected without opening existing shared directories:

1. Docker Compose file-backed secrets retain host ownership. A `0600 root:root` source was unreadable to the non-root controller. The host directory remains root-only, while the individual bind-mounted files grant read-only access to the pinned container GID.
2. Existing `/srv/zani` and `/etc/zani` parent directories are intentionally root-restricted. The agent workspace was moved to the standard `/var/lib/zani-jenkins-agent` state path, and its root-only connection secret is delivered through systemd credentials. No traverse permission was added to either shared tree.

Installing OpenJDK caused Ubuntu's package maintenance hook to restart the existing `gerrit.service` once. No Gerrit configuration was changed. Gerrit returned to active state, its SSH listener on 29418 was verified, and all application/media containers remained running.

## Administrator access

Keep the UI private and create an SSH tunnel from the operator workstation:

```bash
ssh -L 18081:127.0.0.1:18081 zani
```

Then open `http://127.0.0.1:18081`. The administrator ID is `zani-admin`. Retrieve its generated password only in a trusted local terminal and do not paste it into chat, Git, Jira, or screenshots:

```bash
ssh zani "sudo cat /etc/zani/jenkins/secrets/JENKINS_ADMIN_PASSWORD"
```

## Remaining controlled activation

Completed connection steps:

1. A project Deploy Token with `read_repository` only is stored in the existing controller secret files.
2. The controller was recreated and returned healthy with JCasC credential ID `gitlab-zani-read` loaded.
3. A manual, non-printing `git ls-remote` check verified read access to `refs/heads/dev`.
4. The GitLab project webhook was registered with:
   - URL: `https://i15a105.p.ssafy.io/project/zani-backend-dev`
   - Event: Push events only
   - Branch filter: `dev`
   - SSL verification: enabled
   - Secret token: the existing root-only `GITLAB_WEBHOOK_TOKEN` value

Remaining steps:

1. Review and merge the repository-side Jenkins configuration into `dev`.
2. Test one webhook delivery and verify that exactly one Jenkins build is created for the pushed SHA.
3. Verify the first run performs backend validation and deploys only `zani-backend`.
4. Verify a later non-backend-only `dev` change skips both privileged wrapper calls.

No production deployment should be triggered until the repository credential and first controlled pipeline run have been verified.
