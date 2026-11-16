# Local development / Chạy dự án trên máy local

## Prerequisites / Yêu cầu
- Docker Desktop (running) · JDK 21 · Node.js 24 (frontend, from Sprint 4)
- The Maven wrapper (`backend/mvnw`) downloads the right Maven version — no global Maven needed.

## 1. Start infrastructure / Khởi động hạ tầng

```bash
cp infra/docker/.env.example infra/docker/.env      # first time only / chỉ lần đầu
docker compose -f infra/docker/docker-compose.yml --env-file infra/docker/.env up -d --wait
```

| Service | URL | Credentials (dev only) |
|---|---|---|
| PostgreSQL | `localhost:5433` / db `shiftgrab` | `shiftgrab` / `shiftgrab` |
| Redis | `localhost:6379` | — |
| Keycloak admin | http://localhost:8180/admin | `admin` / `admin` |
| Keycloak realm | `http://localhost:8180/realms/shiftgrab` | see demo users |
| Mailpit (fake SMTP) | http://localhost:8025 | — |

The realm is re-imported from [`infra/keycloak/realm-shiftgrab.json`](../../infra/keycloak/realm-shiftgrab.json) on every container start; changes made in the admin console are lost on restart unless exported back to that file.

🇻🇳 Realm được import lại từ file JSON mỗi lần container khởi động; thay đổi trên admin console sẽ mất khi restart, trừ khi bạn export ngược lại vào file đó.

**Why port 5433?** Many developer machines already run a native PostgreSQL on 5432. On Windows it listens on `localhost` and wins over Docker's port mapping, so the backend silently connects to the wrong server and fails with `FATAL: role "shiftgrab" does not exist`. The Compose stack therefore publishes PostgreSQL on 5433, and `application.yml` defaults to 5433. If you already have an old `infra/docker/.env`, set `DB_PORT=5433` there too.

🇻🇳 **Vì sao dùng cổng 5433?** Nhiều máy đã cài sẵn PostgreSQL chạy ở cổng 5432. Trên Windows, bản cài sẵn đó nghe ở `localhost` và được ưu tiên hơn port của Docker, nên backend kết nối nhầm và báo lỗi `FATAL: role "shiftgrab" does not exist`. Vì vậy Compose để PostgreSQL ở cổng 5433, và `application.yml` mặc định dùng 5433. Nếu bạn đã có file `infra/docker/.env` từ trước, hãy sửa `DB_PORT=5433` trong đó.

### Demo users / Tài khoản demo

| Username | Password | Role |
|---|---|---|
| `manager` | `manager123` | MANAGER |
| `staff1`, `staff2`, `staff3` | `staff123` | STAFF |

## 2. Run the backend / Chạy backend

```bash
cd backend
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```
API: http://localhost:8080 · Swagger UI: http://localhost:8080/swagger-ui.html · Health: http://localhost:8080/actuator/health

## 3. Get an access token for manual testing / Lấy token để test thủ công

The `shiftgrab-dev-cli` client allows the password grant **in development only**.

```bash
curl -s http://localhost:8180/realms/shiftgrab/protocol/openid-connect/token \
  -d grant_type=password -d client_id=shiftgrab-dev-cli \
  -d username=staff1 -d password=staff123 | jq -r .access_token
```
Windows PowerShell (copies the token to the clipboard and confirms):

```powershell
$t = (Invoke-RestMethod -Method Post -Uri http://localhost:8180/realms/shiftgrab/protocol/openid-connect/token -Body @{grant_type='password';client_id='shiftgrab-dev-cli';username='manager';password='manager123'}).access_token; $t | Set-Clipboard; "Token copied ($($t.Length) chars)"
```

Use it as `Authorization: Bearer <token>` (or the *Authorize* button in Swagger UI: paste the token only, without `Bearer`). Tokens expire after **5 minutes**; get a new one when the API answers `401`.

## 4. Run tests / Chạy test

```bash
cd backend && ./mvnw verify
```
Integration tests start their own PostgreSQL via Testcontainers — the Compose stack is **not** required for tests, only Docker.

🇻🇳 Test tích hợp tự khởi động PostgreSQL riêng bằng Testcontainers, nên không cần bật Compose để chạy test, chỉ cần Docker đang chạy.

## 5. Stop / reset — Dừng / xóa dữ liệu

```bash
docker compose -f infra/docker/docker-compose.yml down        # keep data / giữ dữ liệu
docker compose -f infra/docker/docker-compose.yml down -v     # wipe volumes / xóa sạch dữ liệu
```
