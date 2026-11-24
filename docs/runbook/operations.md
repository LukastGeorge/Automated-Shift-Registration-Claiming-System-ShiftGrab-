# Operations runbook: incidents and secrets (SG-90)

> 🇻🇳 **Sổ tay vận hành.** Khi có sự cố: làm theo checklist ở §1, rồi tra playbook theo tên cảnh báo ở §2.
> Đổi mật khẩu hay khóa bí mật: làm theo §3. Lưu ý quan trọng: **nhiều biến trong `.env.prod` chỉ có tác dụng ở lần
> khởi tạo đầu tiên** (mật khẩu PostgreSQL, admin Keycloak, admin Grafana, tài khoản demo). Sửa file `.env.prod` thôi thì
> **không** đổi được mật khẩu thật, mà phải đổi trong hệ thống trước.
>
> Deploy, rollback, backup và restore nằm ở [production.md](production.md); file này không lặp lại các bước đó.

All commands run on the server from `/opt/shiftgrab`, with this shorthand:

```bash
DC="docker compose -f infra/docker/docker-compose.prod.yml --env-file infra/docker/.env.prod"
```

## 1. Incident checklist / Checklist sự cố

1. **Detect:** which alert fired (Grafana → Alerting, or Prometheus `/alerts`), or what did the user report? Note the time.
2. **Assess impact:** can staff log in? Can they claim? Is it one instance or all? `$DC ps` shows health; the *ShiftGrab overview* dashboard shows 5xx, latency and claim outcomes.
3. **Mitigate first, investigate second:**
   - a bad deployment → [roll back](production.md#rollback-by-hand--rollback-thủ-công);
   - one sick instance → restart it, since the edge keeps serving with the other;
   - data damage → [restore](production.md#6-backups-and-restore--sao-lưu-và-khôi-phục-sg-76-nfr-06).
4. **Communicate:** tell the manager what is affected and when it should be back. During a flash sale, say whether claims made so far are safe. They are: a claim is either committed with its audit row or not at all.
5. **Fix the cause**, then **verify**: `node infra/scripts/smoke-test.mjs https://$DOMAIN <manager-pw> <staff-pw>`.
6. **Write it down:** a short post-incident note (template in §4) within two working days. No blame, focus on what to change.

## 2. Playbooks by alert / Xử lý theo từng cảnh báo

### BackendInstanceDown (critical)
The edge keeps serving with the other instance, so users are fine, but there is no redundancy left.

```bash
$DC ps backend-1 backend-2
$DC logs --tail=200 backend-1              # the one that is down
$DC up -d --no-deps backend-1             # recreate it; healthy within ~40 s
```

| In the log | Likely cause → action |
|---|---|
| `OutOfMemoryError` (the container exits on purpose: `ExitOnOutOfMemoryError`) | Heap too small for the load → raise the memory limit in compose (heap = 75 % of it), then investigate the cause |
| `Connection to postgres refused` / Flyway errors | Database down or a failed migration → `$DC ps postgres`, `$DC logs postgres`. A failed migration means rolling back the release |
| Nothing new, the container simply is not running | `docker events --since 1h`, `dmesg \| grep -i oom` (killed by the host) |

### HighServerErrorRate (warning): more than 5 % of API requests answer 5xx
1. Which status? Grafana → *requests by status*. Or `$DC logs --since 15m backend-1 backend-2 | grep -E "ERROR|Unhandled"`.
2. Read it by status:
   - **503 `SERVICE_BUSY`** — the database pool is exhausted. See [connections during a flash sale](production.md#database-connections-during-a-flash-sale--kết-nối-database-khi-flash-sale-sg-81). Check `hikaricp_connections_pending` and slow queries:
     ```bash
     $DC exec postgres psql -U shiftgrab -c "SELECT pid, state, now()-query_start AS age, left(query,80) FROM pg_stat_activity WHERE state <> 'idle' ORDER BY age DESC LIMIT 10;"
     ```
   - **503 `CLAIM_BUSY`** in bursts during a flash sale is expected behaviour (clients retry). It is a problem only if it continues outside one.
   - **500 `INTERNAL_ERROR`** — a bug. The log line has the stack trace. Roll back if it started with a release.
   - **502/504 from the edge** — a backend is restarting or hanging; see BackendInstanceDown.

### ClaimLatencyAboveSlo (warning): claim p95 above 300 ms for 5 minutes
- **During a flash sale:** expected for a minute (SG-80/81 report). Act if it lasts beyond the sale.
- **Otherwise:**
  - Lock waits: `shiftgrab_lock_wait_seconds`. Pool waits: `hikaricp_connections_acquire_seconds`.
  - Database CPU: `docker stats`. Long transactions: the `pg_stat_activity` query above.
  - Redis latency: `$DC exec redis redis-cli --latency` (Ctrl-C to stop).

### ClaimingUnavailable (critical): claims refused with `LOCK_UNAVAILABLE`
Redis is unreachable. Claims **fail closed** by design (ADR-0004), while browsing, cancelling, reports and the rest keep working.

```bash
$DC ps redis && $DC logs --tail=100 redis
$DC exec redis redis-cli ping                       # PONG expected
$DC exec redis redis-cli info memory | grep used_memory_human   # maxmemory is 96 MB, policy noeviction
$DC up -d --no-deps redis                           # restart; locks are short-lived, nothing to recover
```
Live slot updates also pause while Redis is down and resume on their own.

### No alert, but the site is down
| Symptom | Check → action |
|---|---|
| Browser: certificate error | `$DC logs certbot`; certificates renew twice a day. Manual renewal: [production.md §3](production.md#3-first-tls-certificate--chứng-chỉ-tls-lần-đầu) |
| nginx default page or 404 everywhere | `$DC logs edge \| head -20`. "*not executable*" means the scripts lost their executable bit (fixed in SG-82; CI checks it) |
| Login page broken / redirect loop | `$DC logs keycloak`; `KC_HOSTNAME` must equal `SHIFTGRAB_PUBLIC_URL/auth` |
| `backup` shows *unhealthy* | No successful backup for 26 h → `$DC logs backup`; run one now ([production.md §6](production.md#6-backups-and-restore--sao-lưu-và-khôi-phục-sg-76-nfr-06)) |
| Grafana `502`, container *Up* but at very high CPU | Memory limit too tight. It is fixed in compose (only the Prometheus plugin runs, 256 MiB); check that `GF_PLUGINS_DISABLE_PLUGINS` is still set after upgrades |
| Disk full | `df -h`, `docker system df`; prune old images with `docker image prune -a --filter "until=168h"` (never `docker volume prune`: it deletes the database) |

## 3. Rotating secrets / Xoay vòng secret

**Rule:** change the secret *in the system* first, then in `.env.prod` (or the GitHub secret), then restart whatever reads it. For a planned rotation, generate each new value with `openssl rand -base64 32`. Rotate immediately after anyone with access leaves, or if a value may have leaked.

| Secret | Read at | Downtime |
|---|---|---|
| `DB_PASSWORD` | Every backend connection, backups | None (one instance at a time) |
| `KEYCLOAK_DB_PASSWORD` | Keycloak start-up | Logins ~1 min (issued tokens keep working) |
| `KEYCLOAK_ADMIN_PASSWORD` | **First start only** | None |
| `GRAFANA_ADMIN_PASSWORD` | **First start only** | None |
| `DEMO_*_PASSWORD` | **First realm import only** | None |
| Realm signing key | Every token | None (overlap) |
| `DEPLOY_SSH_KEY`, `GHCR_READ_TOKEN` | CD workflow | None |

### Database password of the application (`DB_PASSWORD`)
PostgreSQL applies `POSTGRES_PASSWORD` only when it creates the database. Changing `.env.prod` alone would lock the backends out.

```bash
$DC exec postgres psql -U shiftgrab -d shiftgrab -c '\password shiftgrab'   # prompts; nothing in shell history
# edit infra/docker/.env.prod: DB_PASSWORD=<new>
$DC up -d --no-deps backend-1 && sleep 45 && $DC ps backend-1     # healthy before touching the next one
$DC up -d --no-deps backend-2 && $DC up -d --no-deps backup
```
Existing pooled connections stay valid, because a password is only checked at connect time. Each backend switches when it restarts. The `postgres` container will be recreated with the new environment at the next `$DC up -d`; that is harmless.

### Keycloak's database password (`KEYCLOAK_DB_PASSWORD`)
```bash
$DC exec postgres psql -U shiftgrab -d shiftgrab -c '\password keycloak'
# edit .env.prod: KEYCLOAK_DB_PASSWORD=<new>
$DC up -d --no-deps keycloak            # logins unavailable ~1 min; tokens already issued keep working
```

### Keycloak admin, and the demo / staff accounts
The admin console is not reachable from the internet (the edge answers 404), so use `kcadm` inside the container:

```bash
$DC exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080/auth --realm master --user admin          # prompts for the current password
$DC exec keycloak /opt/keycloak/bin/kcadm.sh set-password -r master --username admin --new-password '<new>'
$DC exec keycloak /opt/keycloak/bin/kcadm.sh set-password -r shiftgrab --username staff1 --new-password '<new>'
```
Then update `.env.prod` (to keep it truthful for a rebuild) and the GitHub secrets `DEMO_MANAGER_PASSWORD` and `DEMO_STAFF_PASSWORD`, which the post-deployment smoke test uses.

### Grafana admin
Use the HTTP API through the edge. `grafana cli admin reset-admin-password` inside the running container hung for
more than 5 minutes when tested (it waits for the database the server holds), so it is not used here.

```bash
read -rs OLD; read -rs NEW     # type the current and the new password; nothing in shell history
curl -fsS -u "admin:$OLD" -X PUT -H 'Content-Type: application/json' \
  -d "{\"oldPassword\":\"$OLD\",\"newPassword\":\"$NEW\",\"confirmNew\":\"$NEW\"}" \
  "https://$DOMAIN/grafana/api/user/password"        # → {"message":"User password changed"}
# then GRAFANA_ADMIN_PASSWORD=<new> in .env.prod
```

### Realm signing key (tokens)
Add a new key with a higher priority and keep the old one until every token it signed has expired. Access tokens live 5 minutes and sessions 30 minutes idle. Backends fetch the new public key by its `kid` on their own.

```bash
KC="$DC exec keycloak /opt/keycloak/bin/kcadm.sh"
$KC create components -r shiftgrab -s name=rsa-$(date +%Y%m) -s providerId=rsa-generated \
  -s providerType=org.keycloak.keys.KeyProvider -s 'config.priority=["200"]'
# after 35 minutes: make the old key passive (still verifies, no longer signs), and a day later delete it
$KC get components -r shiftgrab -q type=org.keycloak.keys.KeyProvider --fields id,name,config
$KC update components/<old-id> -r shiftgrab -s 'config.active=["false"]'
```

### Deployment credentials (GitHub)
- **`DEPLOY_SSH_KEY`:**
  1. Generate `ssh-keygen -t ed25519 -f deploy_new -C shiftgrab-deploy`.
  2. Append `deploy_new.pub` to the deploy user's `~/.ssh/authorized_keys`.
  3. Replace the secret (Settings → Environments → production).
  4. Run *Actions → CD → Run workflow* with the current version to prove it works.
  5. Remove the old public key line.
- **`GHCR_READ_TOKEN`** (only if packages are private): create a new token with `read:packages`, replace the secret, then revoke the old token.
- **`DEPLOY_KNOWN_HOSTS`:** update it only when the server's host key changes. A host-key mismatch is a reason to stop and investigate, not to overwrite.

## 4. Post-incident note (template)

```markdown
## <date> <one-line title>
- Impact: who could not do what, from–to (time zone)
- Detection: alert / user report; time to detect
- Timeline: hh:mm events
- Root cause: (why, not who)
- What helped / what slowed us down:
- Actions: [ ] change … (owner, date)
```
