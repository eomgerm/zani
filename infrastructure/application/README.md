# Application Stack

This stack runs the `dev` branch backend on the existing single EC2 host. The frontend is deployed separately with Vercel.

## Network layout

- Host Nginx owns public ports 80 and 443.
- Backend is published only on `127.0.0.1:18080`.
- MySQL and Application Redis are reachable only inside `zani-application-internal`.
- Media Redis remains a separate service and data store.
- No additional UFW rule is required for this stack.
- Nginx routes only backend API and Swagger paths; Vercel serves the frontend.

## Required secret files

The Compose file reads these files from `/etc/zani/application/secrets`:

- `mysql_root_password`
- `mysql_app_password`
- `redis_app_password`
- `jwt_secret`

Do not commit secret values to Git. Creating the server-side directory and applying restrictive file permissions requires explicit operator approval.

`FRONTEND_ORIGIN` must be supplied to Docker Compose as the stable HTTPS Vercel deployment origin. Vercel preview URLs are not covered by the current exact-origin CORS configuration.

## Schema policy

The stack preserves the `dev` profile's `spring.jpa.hibernate.ddl-auto=validate` policy. The current `dev` branch has no migration tool or schema migration files, so migrations must be added before deploying persistent entities that require database tables.

## Verification order

1. `docker compose config`
2. Build the backend image.
3. Start MySQL and Application Redis and wait for healthy status.
4. Start the backend and wait for `/actuator/health` to report healthy.
5. Add the Nginx API locations and run `nginx -t` before reload.
6. Verify HTTPS API, Vercel CORS, LiveKit signaling, media, Egress, Gerrit, and SSH regression checks.
