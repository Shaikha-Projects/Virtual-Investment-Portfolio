# EC2 deployment — Amazon Linux 2023, us-east-1

This is a separate Compose project (`investment-aws`). It does not merge with or change the local `docker-compose.yml`, Jenkinsfile, or backend images. Run every command below from the **repository root** on EC2 unless stated otherwise. Nothing here has been run against AWS by the coding agent.

## Capacity and deployment gates

**Resize before running the complete stack.** t3.micro has 1 GiB RAM; 2 GiB swap does not supply JVM working memory or prevent swap thrashing. A local read-only measurement of the existing containers was approximately Auth 386 MiB, Investment 620 MiB, Notification 275 MiB, Kafka 1.14 GiB, PostgreSQL 83 MiB, excluding the OS and Jenkins. These are observations, not guaranteed EC2 requirements.

Use **t3.medium (4 GiB RAM, x86_64)** as the smallest practical starting point for a low-traffic demonstration. The new caps total 3,200 MiB, leaving roughly 896 MiB for the OS/Docker. JVM heap caps are smaller than container limits to leave native/metaspace headroom. Load-test startup, AI embeddings and concurrent users: 4 GiB is a starting budget, not a capacity guarantee. Use t3.large (8 GiB) if memory pressure, sustained swap activity, OOM kills or high latency persists. Monitor T3 CPU credits as well. Do not switch to ARM/t4g without publishing compatible images. Resize manually in AWS; this guide does not change the instance.

Use enough EBS free space for three service images, PostgreSQL, Kafka, Docker layers and backups (start with a 30 GiB volume and monitor). Do not run Jenkins or Maven builds here. The frontend build is a separate Node build container and should run **before** starting the stack, reducing peak memory competition.

**Known backend limitation:** `notification-service/.../Service/EmailService.java` hardcodes `http://localhost:8084/auth/verify?token=...` (line 69) and `http://localhost:5173/?token=...` (line 135). No existing property changes these. Emails can be sent, but their links do not work remotely. For a controlled demo only, the recipient can replace the verification link origin/path with `https://YOUR_DOMAIN/api/auth/auth/verify?token=...`, or the reset origin with `https://YOUR_DOMAIN/?token=...`, keeping the original token private. This is **not** a finished public signup/recovery experience. A separate approved backend change and CI image release is required to parameterize these links before public release. No backend logic or image is modified here.

HTTPS with a trusted certificate is required before login, registration or payments with real credentials. HTTP mode below is only for non-sensitive connectivity checks. TLS needs a domain pointing to this instance; an IP-only deployment is not covered by this certificate procedure. Do not treat an open security-group port 443 as TLS configuration.

## 1. Get the deployment files onto EC2

These new files must first be committed/pushed from your development machine, or transferred securely. They are not automatically present in the already-cloned EC2 repository. If using Git, review `git diff`/`git status` and commit **only `deploy/aws/`**; never add `.env`, certificates, backups or uploads. Pushing main triggers the existing Jenkins polling pipeline; Jenkins itself is unchanged.

In the EC2 terminal, enter your existing repository directory, then:

```bash
git status --short
# Stop if there are local edits; do not reset or overwrite them.
git pull --ff-only
docker version
docker compose version
uname -m
free -h
df -h / /var/lib/docker
docker ps
```

Expect x86_64, Docker accessible to your current user, and at least 4 GiB RAM before proceeding. If Docker needs sudo, use `sudo docker` consistently or finish the existing Docker group setup and reconnect; do not chmod the socket. Do not install/replace Docker or Compose just for this guide. Keep SSH restricted, allow TCP 80/443 only as needed; do **not** open 5432, 9092, 8083, 8084 or 8085.

The instance also needs outbound internet/DNS access: HTTPS 443 for registries, npm, Stripe and Gemini, SMTP 587 to the configured Gmail server, and DNS through its configured resolver. Inbound HTTP/HTTPS rules alone do not establish this. There is no need to expose database or broker ports to the EC2 security group.

## 2. Set up the private environment

The minimum values for the enabled feature set are `PUBLIC_HOST`, `PUBLIC_ORIGIN`, `DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_SERVICE_KEY`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `STRIPE_SECRET_KEY`, and `GEMINI_API_KEY`. `ADMIN_EMAIL`/`ADMIN_PASSWORD` are optional, but useful for first deployment. `IMAGE_TAG` defaults to the CI-verified release in the example; all three repositories must have the same tag.

Generate deployment-only DB/JWT/internal secrets without printing them:

```bash
umask 077
test ! -e deploy/aws/.env || { echo 'Environment already exists; preserve it and edit manually.'; exit 1; }
cp deploy/aws/.env.example deploy/aws/.env
python3 - <<'PY'
from pathlib import Path
import base64, secrets
p = Path('deploy/aws/.env')
values = {
    'DB_PASSWORD': secrets.token_hex(24),
    'JWT_SECRET': base64.b64encode(secrets.token_bytes(32)).decode(),
    'INTERNAL_SERVICE_KEY': secrets.token_hex(32),
}
p.write_text(''.join(f"{line.split('=', 1)[0]}='{values[line.split('=', 1)[0]]}'\n"
                     if line.split('=', 1)[0] in values else line + '\n'
                     for line in p.read_text().splitlines()))
PY
chmod 600 deploy/aws/.env
vi deploy/aws/.env
```

Set `PUBLIC_HOST='portfolio.your-domain.example'` to your actual DNS name and `PUBLIC_ORIGIN='https://portfolio.your-domain.example'` with **no trailing slash**. Replace the illustrative domain; do not deploy it literally. Add your Gmail address/app password, existing **sandbox** Stripe secret and Gemini API key in this file. Single-quote values containing `$`, `#` or spaces so Compose treats them literally; do not use shell `source` on this file. Do not paste secrets into terminal commands/history, chat or Git. Supply both admin values to create a new ADMIN/ACTIVE account; existing accounts are never overwritten. After confirming first login, remove the bootstrap password from the file and recreate only Auth on a later maintenance update. Keep the DB password/JWT/internal key stable across restarts; changing an env value does not rotate an existing PostgreSQL password.

`MYFATOORAH_ENABLED=false` preserves the existing unused legacy-provider default; Stripe, AI Help and market simulation remain enabled. No Docker Hub token is needed to pull public images. For private/rate-limited pulls use `docker login --username shaikhahsn` interactively, entering a pull-only token when prompted; never commit it or pass it on the command line.

Define a helper for this terminal session and validate without printing resolved secrets:

```bash
dc() { docker compose --env-file deploy/aws/.env -f deploy/aws/compose.yml "$@"; }
dc config --quiet
dc config --services
dc config --images
git check-ignore deploy/aws/.env
```

Do **not** run bare `docker compose up`: that selects the local development file. Do not print/archive `dc config` without `--quiet`; its resolved output contains secrets. The file permissions protect the disk file; Docker administrators can still inspect container environments.

## 3. Pull images and build the production frontend

```bash
dc pull postgres_db kafka auth-service investment-service notification-service
dc build frontend
```

The backend images are pulled unchanged. The frontend uses the existing package lock and a Node 22 build stage, then serves static `dist` through Nginx. Only public relative routes are built in: `/api/auth`, `/api/investment`, `/api/notification`. No API key goes into the browser bundle. The Dockerfile-specific context ignore excludes `.env`, uploads, backend sources, node_modules and reports. `IMAGE_TAG=build-11-e7c0e6fe6a10` pins the verified CI release. Setting `IMAGE_TAG=latest` is supported but mutable; record the resolved digests for rollback.

The local production-image build succeeded. Its existing package lock reported four npm advisories (three moderate, one high); no dependency versions were changed as part of deployment preparation. Review these before a public release. Node/build dependencies are in the build stage, not the final Nginx filesystem.

## 4. Obtain HTTPS before starting the frontend

Point a domain A record at the instance's public IPv4 (prefer a stable Elastic IP), wait for DNS propagation, and leave port 80 free for ACME HTTP validation. Set the same domain in `PUBLIC_HOST` and HTTPS origin in `.env`. This command obtains a certificate; it does not install Certbot on the host:

```bash
read -r -p 'DNS name (same as PUBLIC_HOST): ' DOMAIN
read -r -p 'Certificate contact email: ' CERT_EMAIL
mkdir -p deploy/aws/certificates deploy/aws/certbot-work deploy/aws/certbot-logs
docker run --rm -p 80:80 \
  -v "$PWD/deploy/aws/certificates:/etc/letsencrypt" \
  -v "$PWD/deploy/aws/certbot-work:/var/lib/letsencrypt" \
  -v "$PWD/deploy/aws/certbot-logs:/var/log/letsencrypt" \
  certbot/certbot certonly --standalone --non-interactive --agree-tos \
  --email "$CERT_EMAIL" -d "$DOMAIN"
```

If port 80 is occupied, inspect the owner; do not stop unrelated containers/processes. If this deployment's HTTP frontend was started for a smoke check, `dc stop frontend` releases it. TLS commands need a valid public domain; if unavailable, stop before submitting credentials. Optional HTTP-only non-sensitive smoke: `dc up -d --wait --wait-timeout 600` followed by the unauthenticated curl checks below; then `dc stop frontend` before obtaining the certificate.

Enable the TLS overlay in the helper for **all subsequent commands** (repeat the helper definition in a new shell):

```bash
dc() { docker compose --env-file deploy/aws/.env -f deploy/aws/compose.yml -f deploy/aws/tls.yml "$@"; }
dc config --quiet
dc up -d --wait --wait-timeout 600
dc ps
dc exec frontend nginx -t
```

Only frontend ports 80/443 are published. PostgreSQL, Kafka and all three service ports are private to Docker's bridge network. This is not an `internal: true` network because SMTP, Gemini and Stripe require outbound connectivity. Nginx forwards bearer headers, strips only the new public service prefix, and sets trusted origin/port headers; Spring's environment-only forwarded-header setting allows same-origin requests without widening the localhost CORS allowlists. Nginx blocks the public internal-user endpoint, preserves uploads and `/wallet` payment returns, disables SSE buffering and uses a 1,900-second notification idle timeout. Query strings are excluded from Nginx access logs to avoid recording email/reset tokens.

Startup waits for PostgreSQL/Kafka health and backend HTTP security responses. Backend checks establish that the HTTP/security layer responds; they do not prove mail delivery, Gemini, Stripe, authenticated queries or Kafka business events. `restart: unless-stopped` restarts exited containers, not containers that merely become unhealthy.

### First database startup

The new AWS project has separate named volumes (`investment-aws_*`), not the local ones. On a **new empty PostgreSQL volume only**, the official entrypoint runs the existing `investment_schema.sql`, then `assets_data.sql`: six original assets, four ACTIVE. Hibernate remains `validate`. It does not copy local users, holdings or transactions, and never reseeds an existing database. Do not load the asset SQL again or delete the volume to fix an initialization failure; inspect logs and back up first. If you intend a full data migration, stop before first startup and plan a consistent private backup/restore instead of combining a full dump with these seed scripts.

## 5. Verify deployment

Use your actual origin, with no secrets in these commands:

```bash
read -r -p 'Public origin (https://your-domain): ' ORIGIN
curl -fsS "$ORIGIN/healthz"
curl -fsS -o /dev/null -w 'Frontend: %{http_code}\n' "$ORIGIN/"
curl -fsS -o /dev/null -w 'Wallet return: %{http_code}\n' "$ORIGIN/wallet?payment=cancelled"
curl -sS -o /dev/null -w 'Auth (401 or 403): %{http_code}\n' "$ORIGIN/api/auth/auth/me"
curl -sS -o /dev/null -w 'Assets (401): %{http_code}\n' "$ORIGIN/api/investment/assets"
curl -sS -o /dev/null -w 'SSE without JWT (401): %{http_code}\n' "$ORIGIN/api/notification/notifications/subscribe"
curl -sS -o /dev/null -w 'Internal endpoint blocked (404): %{http_code}\n' "$ORIGIN/api/auth/internal/users/1"
dc exec -T postgres_db pg_isready -U postgres -d investment_portfolio
dc exec -T postgres_db psql -U postgres -d investment_portfolio -c 'SELECT asset_status, count(*) FROM assets GROUP BY asset_status;'
dc exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list
dc ps
docker stats --no-stream
free -h
vmstat 1 5
dc logs --tail=80 auth-service investment-service notification-service
```

Inspect logs privately: upstream libraries may log sensitive data. Do not publish unredacted logs. Ensure there is no Hibernate validation failure, DB refusal, broker resolution failure, OOM or restart loop. Security group and `dc ps` should expose only frontend, never DB/Kafka/service ports. Check container OOM state with:

```bash
for id in $(dc ps -q); do
  docker inspect --format '{{.Name}} OOM={{.State.OOMKilled}} restarts={{.RestartCount}}' "$id"
done
```

In the browser over HTTPS:

1. Login as the bootstrapped admin; confirm admin navigation and asset/user pages. No backend credentials appear in browser source/network responses.
2. Register a test investor, check SMTP delivery through Notification/Kafka. Apply the documented link-origin workaround only for a controlled demo; public email flows remain a release blocker until fixed properly. Verify login, session checks and profile picture upload/retrieval.
3. Confirm all four ACTIVE assets and both charts. Verify two DB reads ~12 seconds apart show changed prices/timestamps:

```bash
dc exec -T postgres_db psql -U postgres -d investment_portfolio -c "SELECT symbol,current_price,updated_at FROM assets WHERE asset_status='ACTIVE' ORDER BY symbol;"
sleep 12
dc exec -T postgres_db psql -U postgres -d investment_portfolio -c "SELECT symbol,current_price,updated_at FROM assets WHERE asset_status='ACTIVE' ORDER BY symbol;"
```

4. Use the existing sandbox Wallet deposit flow; verify Stripe returns to HTTPS `/wallet`, recovers/verifies the operation and updates balance once. No new webhook endpoint is added; existing redirect/recovery behavior is preserved.
5. Make a small demo buy/sell or order using the existing UI; confirm holdings, transactions and notifications. Browser Network should show `/api/notification/notifications/subscribe` connected with `text/event-stream`; a pending stream is normal. Confirm event toasts arrive promptly, rather than waiting for the stream to close. This proves more than just Kafka's topic-list health check.
6. Test watchlist, alerts, AI Help response/source display, profile and history. Verify no browser requests target localhost, mixed HTTP content, CORS errors, 502/504 or changed API payloads. Mobile layout remains the existing frontend.

## 6. Operations, renewal and updates

Back up the database privately before upgrades; this example creates a logical dump without printing credentials:

```bash
umask 077
mkdir -p deploy/aws/backups
dc exec -T postgres_db pg_dump -U postgres -d investment_portfolio -Fc > "deploy/aws/backups/portfolio-$(date +%Y%m%d-%H%M%S).dump"
```

Also back up profile uploads, environment and certificate keys securely; EBS/volume snapshots require an appropriate consistency plan. Never commit these. A single EC2/broker/database is suitable for a project demonstration, not high availability.

Certificate renewal is **not automated** by these files. Schedule it separately before expiry; standalone renewal requires a brief frontend-only interruption. Backends and volumes remain running:

```bash
dc stop frontend
docker run --rm -p 80:80 \
  -v "$PWD/deploy/aws/certificates:/etc/letsencrypt" \
  -v "$PWD/deploy/aws/certbot-work:/var/lib/letsencrypt" \
  -v "$PWD/deploy/aws/certbot-logs:/var/log/letsencrypt" \
  certbot/certbot renew --standalone
# Run this even if renewal fails, to bring the frontend back with its current certificate.
dc up -d --no-deps frontend
```

For a later CI-tested image update, back up first, select its common `IMAGE_TAG` in `.env`, then:

```bash
dc pull auth-service investment-service notification-service
dc up -d --no-deps --wait --wait-timeout 600 auth-service investment-service notification-service
```

Repeat verification. For frontend source updates run `dc build frontend`, then `dc up -d --no-deps frontend`. To roll back, select the previous known-good common image tag and repeat the pull/up commands; database compatibility must still be checked. Do not use `down -v`, volume prune, database resets or manual reseeding.

References: [EC2 T3 sizes](https://aws.amazon.com/ec2/instance-types/t3/), [Compose readiness](https://docs.docker.com/compose/how-tos/startup-order/), [PostgreSQL initialization](https://github.com/docker-library/postgres/blob/master/18/trixie/docker-entrypoint.sh), [Nginx proxy behavior](https://nginx.org/en/docs/http/ngx_http_proxy_module.html), [Certbot Docker instructions](https://eff-certbot.readthedocs.io/en/stable/install.html#running-with-docker).

## Preparation checks actually executed locally

- HTTP and TLS Compose configurations validated with synthetic environment values; only frontend ports are published and the TLS template override merges correctly.
- Production frontend Docker image built successfully with the existing package lock and same-origin API settings.
- Both Nginx templates passed `nginx -t`; TLS-mode HTTP and HTTPS health checks passed using a synthetic self-signed certificate in an isolated local test container.
- Eight gateway checks passed against synthetic upstream services: Auth/Investment/upload routing and headers, blocked internal endpoint, wallet SPA fallback, HTTPS redirect, SSE event forwarding, and absence of localhost backend URLs in production JavaScript.
- All 12 Bash command blocks passed syntax parsing. Environment/certificate/backup paths are Git-ignored.
- The five existing local application containers retained their original IDs, start times, restart counts and mounts. Temporary verification containers/network were removed.

These are preparation checks, **not AWS integration results**. The full stack was not started on EC2 or locally under the new resource caps. Public DNS/certificates, schema initialization, authenticated origin/CORS behavior with the published services, mail/Kafka business events, Stripe returns, Gemini and sustained memory capacity still require the deployment verification above. Existing CI test results are not being represented as a new test-suite execution here.
