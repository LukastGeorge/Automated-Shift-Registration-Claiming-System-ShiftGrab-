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

## 0. Prepare the server / Chuẩn bị máy chủ (SG-74)

A VPS with ~4 GB RAM and Ubuntu 24.04. From your laptop, create a key for the deploy user (`ssh-keygen -t ed25519 -f ~/.ssh/shiftgrab_deploy`), then as root on the server:

```bash
curl -fsSLO https://raw.githubusercontent.com/LukastGeorge/Automated-Shift-Registration-Claiming-System-ShiftGrab-/main/infra/scripts/provision-vps.sh
less provision-vps.sh          # read it before running it as root
sh provision-vps.sh "$(cat shiftgrab_deploy.pub)"
```

It installs security updates (and turns on automatic ones), sets the time zone, adds 2 GB swap, installs Docker from Docker's repository, creates the `deploy` user (SSH key only, member of `docker`, no sudo), **disables root and password logins**, opens only **22/80/443** with `ufw`, and enables **fail2ban** for SSH. Keep the root session open until `ssh deploy@<server>` works in a second terminal. Note: Docker writes its own firewall rules for published ports, which `ufw` does not filter; that is why only the edge proxy publishes ports.

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

## 6. Backups and restore / Sao lưu và khôi phục (SG-76, NFR-06)

The `backup` service runs `pg_dump` (custom format) of **both** databases, `shiftgrab` and `keycloak`, at start-up and every day at `BACKUP_AT` (02:30 Vietnam time), keeping `BACKUP_KEEP_DAYS` (7) days. Files are written to `.part` first, so a crash never leaves a truncated file that looks valid. The service is **unhealthy when the last successful backup is older than 26 h**, so a silently failing backup shows up in `docker compose ps`.

```bash
docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.prod exec backup sh /usr/local/bin/backup.sh --once   # backup now
sh infra/scripts/restore.sh                                     # list backups
sh infra/scripts/restore.sh shiftgrab-20261123T023000Z.dump     # restore one
```

`restore.sh` stops the services using that database (backends, or Keycloak), restores in **one transaction** (`--clean --if-exists --single-transaction`: all or nothing), and starts them again.

**Restore drill (done before release):** created data → backup → deleted all shifts and registrations → `restore.sh` → data back (shifts, registrations and the append-only audit log), backends healthy, smoke test 12/12. The restore took **15 s**, far inside the 1 h RTO.

**Limit:** the backups live on the same server, so they protect against mistakes and bad deployments, not against losing the server. Set `BACKUP_DIR` to a host path and copy it off the machine daily (for example `rclone sync /var/backups/shiftgrab remote:shiftgrab-backups` from cron). This is the next step once a server exists.

## 7. Monitoring / Giám sát (SG-72)

- **Grafana:** `https://$DOMAIN/grafana/` (user `admin`, password `GRAFANA_ADMIN_PASSWORD`). Dashboard **ShiftGrab overview**: instances up, request rate by status, claim p95/p99 against the 300 ms SLO (NFR-02), claim outcomes per minute, lock wait p95, 5xx ratio, JVM heap, DB connections. Datasource and dashboard are provisioned from `infra/grafana/` (configuration as code: edit the JSON, not the UI). `GRAFANA_ANONYMOUS=true` gives visitors read-only access for a demo.
- **Prometheus:** internal only, scrapes `/actuator/prometheus` on both backends every 15 s, keeps 7 days. Alert rules in `infra/prometheus/alerts.yml`: instance down, 5xx > 5 %, claim p95 > 300 ms, claims refused because Redis is down. They show in Grafana/Prometheus; sending them to e-mail or chat (Alertmanager) is a later step.
- Check the config before committing: `docker run --rm --entrypoint promtool -v "$PWD/infra/prometheus:/etc/prometheus" prom/prometheus:v3.15.1 check config /etc/prometheus/prometheus.yml`

### Database connections during a flash sale / Kết nối database khi flash sale (SG-81)

Claims on one shift queue inside PostgreSQL (ADR-0004 revision), so the connection pool is the resource to watch.

| Setting | Value | Why |
|---|---|---|
| `DB_POOL_SIZE` (per backend) | 25 | Measured: 10 made the pool the bottleneck (average wait 233 ms); 25 brought it to about 1 ms |
| `KC_DB_POOL_MAX_SIZE` | 20 | Keycloak's default is 100, enough to starve the backends on its own |
| Budget | 2 × 25 + 20 + ~5 reserve = **75 < 100** (`max_connections`) | Re-do this sum before adding a backend |
| Hikari `connectionTimeout` | 1 s (`DB_CONNECTION_TIMEOUT_MS`) | Fail fast: `503 SERVICE_BUSY` + `Retry-After`, not a 30 s hang |
| `CLAIM_LOCK_TIMEOUT` | 1 s | Longest wait for a shift row. 200 ms produced 30 needless `CLAIM_BUSY` in the load test |

- **Watch:** `hikaricp_connections_acquire_seconds` (pool wait), `hikaricp_connections_pending`, `shiftgrab_claims_total{result="CLAIM_BUSY"}` and `{result="SERVICE_BUSY"}`, plus 5xx in the dashboard.
- **Scaling past ~3 backends:** the budget no longer fits. Put **PgBouncer in transaction mode** between the backends and PostgreSQL. The claim timeouts are `SET LOCAL`, which is compatible with it.
- 🇻🇳 Khi flash sale, hàng chờ nằm trong PostgreSQL nên phải canh pool kết nối. Tổng 2×25 (backend) + 20 (Keycloak) + dự phòng ≈ 75, dưới mức tối đa 100. Thêm backend thì tính lại; từ khoảng 3 backend trở lên thì đặt PgBouncer (transaction mode) ở giữa.

## Test the production stack on a laptop / Chạy thử stack production trên máy

Use `DOMAIN=localhost`, `SHIFTGRAB_PUBLIC_URL=http://localhost:8088`, `EDGE_HTTP_PORT=8088` and locally built images:

```bash
docker build -t localtest/shiftgrab-backend:dev backend
docker build -t localtest/shiftgrab-frontend:dev frontend
# .env.prod with IMAGE_PREFIX=localtest and SHIFTGRAB_VERSION=dev
docker compose -f infra/docker/docker-compose.prod.yml --env-file <file> up -d
node infra/scripts/smoke-test.mjs http://localhost:8088 <manager-password> <staff-password>
# Browser journey (SG-82): see e2e/README.md
(cd e2e && npm ci && npx playwright install chromium && \
  E2E_MANAGER_PASSWORD=<manager-password> E2E_STAFF_PASSWORD=<staff-password> npx playwright test)
docker compose -f infra/docker/docker-compose.prod.yml --env-file <file> down -v   # -v deletes the test data
```

The same browser journey runs in CI (`e2e.yml`): required on every pull request into `main`, nightly on `develop`, and on pull requests labelled `e2e`. A failed run uploads the Playwright trace, screenshots, video and container logs as the `e2e-report` artifact.

## Infrastructure CI / CI cho hạ tầng

`ci-infra.yml` runs when `infra/` changes: ShellCheck on every script, `docker compose config` on both Compose files, `promtool` on the Prometheus configuration and alert rules, JSON syntax of the dashboard and realms.

## 🇻🇳 Tóm tắt
- **Chuẩn bị máy chủ:** chạy `provision-vps.sh` một lần với quyền root. Kịch bản tạo user `deploy` chỉ đăng nhập được bằng SSH key, tắt đăng nhập root và đăng nhập bằng mật khẩu, bật tường lửa (chỉ mở 22/80/443) và fail2ban, tự cài bản vá bảo mật, cài Docker và thêm swap.
- Stack production gồm 9 container trên một VPS. Chỉ có **edge nginx** mở cổng ra ngoài: nó lo TLS, cân bằng tải giữa 2 backend, chuyển tiếp WebSocket, và chuyển `/auth` sang Keycloak.
- **Bí mật** chỉ nằm trong `.env.prod` (`chmod 600`), không bao giờ commit.
- **Chứng chỉ TLS:** lần đầu chạy certbot thủ công, sau đó tự gia hạn.
- **Kiểm tra sau mỗi lần deploy:** chạy `infra/scripts/smoke-test.mjs` (12 bước).
- **Backup:** `pg_dump` cả hai database lúc 02:30 mỗi ngày, giữ 7 ngày. Service `backup` chuyển sang unhealthy nếu quá 26 giờ không có bản backup thành công. Khôi phục bằng `restore.sh`; đã diễn tập thật và khôi phục xong trong 15 giây. Giới hạn: bản backup đang nằm trên cùng máy chủ, nên bước tiếp theo là sao chép ra ngoài (rclone).
- **CD:** gắn tag `v*` là GitHub Actions tự build image, đẩy lên GHCR rồi quét bảo mật. Khi đã bật `DEPLOY_ENABLED`, nó tiếp tục deploy kiểu rolling (lần lượt từng backend, API không bị gián đoạn), chạy smoke test, và tự rollback nếu smoke test thất bại. Rollback thủ công bằng `deploy.sh --rollback`.
- **Chạy thử trên máy:** dùng `DOMAIN=localhost` và cổng 8088.
