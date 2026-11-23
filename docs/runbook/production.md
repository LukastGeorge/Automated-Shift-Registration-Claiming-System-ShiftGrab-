# Production / Vận hành production

The production stack is one VPS running Docker Compose ([ADR-0006](../adr/0006-vps-docker-compose.md)). Files: [`infra/docker/docker-compose.prod.yml`](../../infra/docker/docker-compose.prod.yml), [`infra/nginx/`](../../infra/nginx), [`infra/keycloak/realm-shiftgrab.prod.json`](../../infra/keycloak/realm-shiftgrab.prod.json).

```
Internet ─▶ edge nginx :80/:443 ─┬─ /        ─▶ frontend   (static app, config.json from env)
            TLS (Let's Encrypt)  ├─ /api/    ─▶ backend-1 ┐ least_conn
                                 ├─ /ws      ─▶ backend-2 ┘ WebSocket upgrade
                                 └─ /auth/   ─▶ keycloak   (own database)
            private network: postgres · redis · mailpit · certbot
```

Only the edge publishes ports. `/actuator` and the Keycloak admin console are not reachable from the internet.

## 1. Configure / Cấu hình

```bash
cp infra/docker/.env.prod.example infra/docker/.env.prod
chmod 600 infra/docker/.env.prod          # secrets: readable by the deploy user only
# fill in DOMAIN, SHIFTGRAB_PUBLIC_URL and every "change-me" (openssl rand -base64 32)
```

## 2. Start / Khởi động

```bash
docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.prod up -d
docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.prod ps   # all healthy
```

Without a certificate the edge serves plain HTTP and answers Let's Encrypt challenges.

## 3. First TLS certificate / Chứng chỉ TLS lần đầu

DNS of `DOMAIN` must point to the server and port 80 must be open.

```bash
docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.prod run --rm --entrypoint certbot certbot \
  certonly --webroot -w /var/www/certbot -d "$DOMAIN" --email you@example.com --agree-tos --no-eff-email
docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.prod restart edge   # switches to HTTPS
```

Renewal is automatic: the `certbot` service tries twice a day, the edge re-reads certificates every 6 hours.

## 4. Smoke test / Kiểm tra nhanh

```bash
node infra/scripts/smoke-test.mjs https://$DOMAIN <manager-password> <staff-password>
```

It checks: the app and `config.json`, actuator and admin console hidden, no password-grant client, PKCE login through `/auth`, token issuer = public URL, API through the load balancer, claim, live update over `/ws`. CD runs it after every deployment (SG-75).

## 5. Continuous deployment / Triển khai tự động (SG-75)

`.github/workflows/cd.yml`, triggered by a release tag `v*`:

1. **Images:** build backend and frontend, push to GHCR as `0.7.0`, `0.7` and `sha-<commit>`, with SBOM and provenance attestations; Trivy gate on the pushed images.
2. **Deploy** (only when the repository variable `DEPLOY_ENABLED=true`): copy `infra/` to `/opt/shiftgrab` (secrets excluded), run `infra/scripts/deploy.sh <version>` over SSH (pinned host key), smoke-test the live site, **roll back automatically** if the test fails.

`deploy.sh` does a **rolling update**: backend-1 is replaced and must be healthy before backend-2 is touched. Measured locally: 647 API requests during an update, 0 failed. A claim (POST) that reaches an instance at the exact moment it stops can get a 502, because nginx deliberately never retries non-idempotent requests on the other instance; the user simply tries again. **Database migrations must work with the previous version for one release** (add first, remove later), since both versions run side by side for a minute.

### One-time setup / Cài đặt một lần
- GitHub → Settings → Environments → `production`: variables `PUBLIC_URL`, `DEPLOY_HOST`, `DEPLOY_USER`; secrets `DEPLOY_SSH_KEY` (private key of the deploy user), `DEPLOY_KNOWN_HOSTS` (`ssh-keyscan -t ed25519 <host>`), `DEMO_MANAGER_PASSWORD`, `DEMO_STAFF_PASSWORD`; optional `GHCR_READ_TOKEN` if the packages stay private (a token with `read:packages`).
- Repository variable `DEPLOY_ENABLED=true`. Until then the workflow only builds, scans and publishes images.

### Rollback by hand / Rollback thủ công
```bash
cd /opt/shiftgrab && sh infra/scripts/deploy.sh --rollback      # last version known to work
cd /opt/shiftgrab && sh infra/scripts/deploy.sh 0.6.0           # or any published version
```
`--rollback` returns to the current version if the last deployment did not finish, otherwise to the one before it. The same is available from GitHub: *Actions → CD → Run workflow* with a version.

## 6. Monitoring / Giám sát (SG-72)

- **Grafana:** `https://$DOMAIN/grafana/` (user `admin`, password `GRAFANA_ADMIN_PASSWORD`). Dashboard **ShiftGrab overview**: instances up, request rate by status, claim p95/p99 against the 300 ms SLO (NFR-02), claim outcomes per minute, lock wait p95, 5xx ratio, JVM heap, DB connections. Datasource and dashboard are provisioned from `infra/grafana/` (configuration as code: edit the JSON, not the UI). `GRAFANA_ANONYMOUS=true` gives visitors read-only access for a demo.
- **Prometheus:** internal only, scrapes `/actuator/prometheus` on both backends every 15 s, keeps 7 days. Alert rules in `infra/prometheus/alerts.yml`: instance down, 5xx > 5 %, claim p95 > 300 ms, claims refused because Redis is down. They show in Grafana/Prometheus; sending them to e-mail or chat (Alertmanager) is a later step.
- Check the config before committing: `docker run --rm --entrypoint promtool -v "$PWD/infra/prometheus:/etc/prometheus" prom/prometheus:v3.15.1 check config /etc/prometheus/prometheus.yml`

## Test the production stack on a laptop / Chạy thử stack production trên máy

Use `DOMAIN=localhost`, `SHIFTGRAB_PUBLIC_URL=http://localhost:8088`, `EDGE_HTTP_PORT=8088` and locally built images:

```bash
docker build -t localtest/shiftgrab-backend:dev backend
docker build -t localtest/shiftgrab-frontend:dev frontend
# .env.prod with IMAGE_PREFIX=localtest and SHIFTGRAB_VERSION=dev
docker compose -f infra/docker/docker-compose.prod.yml --env-file <file> up -d
node infra/scripts/smoke-test.mjs http://localhost:8088 <manager-password> <staff-password>
docker compose -f infra/docker/docker-compose.prod.yml --env-file <file> down -v   # -v deletes the test data
```

## 🇻🇳 Tóm tắt
- Stack production gồm 9 container trên một VPS. Chỉ có **edge nginx** mở cổng ra ngoài: nó lo TLS, cân bằng tải giữa 2 backend, chuyển tiếp WebSocket, và chuyển `/auth` sang Keycloak.
- **Bí mật** chỉ nằm trong `.env.prod` (`chmod 600`), không bao giờ commit.
- **Chứng chỉ TLS:** lần đầu chạy certbot thủ công, sau đó tự gia hạn.
- **Kiểm tra sau mỗi lần deploy:** chạy `infra/scripts/smoke-test.mjs` (12 bước).
- **CD:** gắn tag `v*` là GitHub Actions tự build image, đẩy lên GHCR rồi quét bảo mật. Khi đã bật `DEPLOY_ENABLED`, nó tiếp tục deploy kiểu rolling (lần lượt từng backend, API không bị gián đoạn), chạy smoke test, và tự rollback nếu smoke test thất bại. Rollback thủ công bằng `deploy.sh --rollback`.
- **Chạy thử trên máy:** dùng `DOMAIN=localhost` và cổng 8088.
