# ADR-0003: Keycloak as identity provider

- **Status:** Accepted
- **Date:** 2026-11-16

## Context
Two roles (MANAGER, STAFF), a browser SPA and several stateless backend instances (NFR-03, NFR-04). Hand-written authentication is a common source of vulnerabilities (password storage, token revocation, brute-force protection).

## Options considered
1. **Self-built Spring Security + JWT.** Full control and good learning value, but we would own password hashing, refresh-token rotation, lockout and reset flows.
2. **Server-side sessions.** Simple, but needs sticky sessions or a shared session store across instances, and fits SPAs poorly.
3. **Keycloak (OAuth2 / OpenID Connect).** Standard protocols, login UI, brute-force detection, MFA-ready, admin console; the backend becomes a stateless **resource server** that only validates JWTs.

## Decision
Option 3. Realm `shiftgrab` is version-controlled (`infra/keycloak/realm-shiftgrab.json`) and imported on container start. The Angular app uses Authorization Code + PKCE. The backend uses `spring-boot-starter-oauth2-resource-server`, maps realm roles to `ROLE_MANAGER` / `ROLE_STAFF`, and provisions a local user row from token claims on first call.

## Consequences
- ➕ No passwords in our database; industry-standard flows.
- ➕ Backend stays stateless; any instance validates any token.
- ➖ Extra container (~512 MB–1 GB RAM) — the main reason the VPS needs 4 GB.
- ➖ Integration tests need a Keycloak Testcontainer or mocked JWTs (`spring-security-test` `jwt()` for slice tests).

## 🇻🇳 Tóm tắt
Dùng **Keycloak** (OAuth2/OIDC) thay vì tự viết JWT hay session. Keycloak lo phần đăng nhập, chống brute-force và quản lý tài khoản; backend chỉ là resource server kiểm tra JWT nên vẫn stateless. Realm được lưu trong repo và tự import khi khởi động. Đánh đổi: tốn thêm khoảng 0,5–1 GB RAM, và test tích hợp phải dùng Keycloak container hoặc JWT giả lập.
