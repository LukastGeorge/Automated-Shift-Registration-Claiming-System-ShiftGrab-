# Security review: OWASP Top 10:2025 (SG-92)

> 🇻🇳 **Tóm tắt:** rà soát ShiftGrab theo OWASP Top 10:2025.
> - **Đã sửa 6 điểm trong SG-92:**
>   - thêm Content-Security-Policy;
>   - sửa lỗi nginx làm mất header `Permissions-Policy` trên trang chính;
>   - giới hạn tốc độ gửi form đăng nhập theo IP;
>   - chính sách mật khẩu cho realm;
>   - chặn ký tự điều khiển trong tên ca và tên mẫu ca;
>   - xử lý 2 cảnh báo CodeQL còn mở, có ghi lý do.
> - **Đã sửa trước đó trong Sprint 8:** log injection, script không có quyền thực thi trên Linux, lỗi hết pool trả về 500.
> - **Rủi ro còn lại được chấp nhận** đều nằm ở bảng cuối, kèm lý do.

| | |
|---|---|
| Date | 2026-11-24 |
| Scope | Backend API, web app, edge nginx, Keycloak realm, Docker images, CI/CD (`v0.8.0` plus SG-92 changes) |
| Method | Checklist per category; evidence from code, configuration, tests and scanners (CodeQL, Trivy, Dependabot) |
| Reviewer | Project author, with AI assistance. A self-review, not an independent penetration test |

## Summary

| # | Category | Status | Main controls |
|---|---|---|---|
| A01 | Broken Access Control | ✅ | Role rules on URLs and `@PreAuthorize`; ownership checks; deny by default |
| A02 | Security Misconfiguration | ✅ fixed in SG-92 | CSP added; header inheritance bug fixed; internal endpoints hidden |
| A03 | Software Supply Chain Failures | ✅ | Lockfiles, `npm ci`, pinned versions, Dependabot, Trivy gate, SBOM and provenance |
| A04 | Cryptographic Failures | ✅ | TLS 1.2/1.3 and HSTS at the edge; JWT signatures checked; no secrets in git |
| A05 | Injection | ✅ fixed in SG-92 | Bound parameters only; CSV formula guard; control characters refused in names |
| A06 | Insecure Design | ✅ | Three layers against overbooking; documented threat-relevant decisions (ADRs) |
| A07 | Authentication Failures | ✅ fixed in SG-92 | Keycloak + PKCE; account lockout; per-address login limit; password policy |
| A08 | Software or Data Integrity Failures | ✅ | Append-only audit log; transactional outbox; signed provenance of images |
| A09 | Security Logging and Alerting Failures | ✅ | Audit log; log-injection fix; Prometheus alerts |
| A10 | Mishandling of Exceptional Conditions | ✅ | Fail closed when Redis is down; generic 500 without internals; timeouts are 503 |

## Findings and fixes

| ID | Finding | Severity | Fix | Where |
|---|---|---|---|---|
| S-1 | No `Content-Security-Policy`: nothing limits the damage of an XSS | Medium | Strict CSP: `script-src 'self'`, no inline script (Angular `inlineCritical` turned off for that), `frame-ancestors 'none'`, `object-src 'none'` | `frontend/security-headers.conf`, `angular.json` |
| S-2 | `index.html` was served **without** `Permissions-Policy`: nginx drops server-level `add_header` in any location that sets its own | Low | One include file with all security headers, included in every location | `frontend/nginx.conf` |
| S-3 | No limit on login attempts per address: Keycloak locks one account after 5 failures, but one address could still try one password on many accounts (credential stuffing) | Medium | Edge limit: 30 login form POSTs per minute per address, burst 20, answers `429`. Sized for a team logging in together behind one Wi-Fi. Verified: 50 rapid POSTs gave 26 passed and 24 `429`; GETs are never limited | `infra/nginx/upstreams.conf`, `locations.conf` |
| S-4 | Production realm had no password policy | Low | `length(8) and maxLength(128) and notUsername and notEmail`, following NIST SP 800-63B (length over composition rules). Verified: a 5-character password is refused ("Độ dài tối thiểu 8"). Keycloak's `notUsername` refuses a password *equal* to the username, not one that contains it | `infra/keycloak/realm-shiftgrab.prod.json` |
| S-5 | Shift and template names accepted line breaks and other control characters, and names flow into e-mail subjects, CSV/XLSX and logs. Only the *edit* request refused `\n`, by accident of its blank-check regex | Low | `@Pattern([^\p{Cntrl}]*)` on create, edit and template names | `ShiftController`, `ShiftTemplateController` |
| S-6 | Log injection: request URI written to logs as is (CodeQL) | Medium | `forLog()` replaces every Unicode line break (`\R`) | `GlobalExceptionHandler` (SG-81) |
| S-7 | Shell scripts not executable in git: on Linux the edge served nginx's default site | High (availability) | `100755` in the index; CI check | SG-82 |
| S-8 | Pool exhaustion and deadlock victims answered `500` | Low | `503` + `Retry-After` (`SERVICE_BUSY`, `CLAIM_BUSY`) | SG-81 |

### CodeQL alerts reviewed

| Alert | Verdict | Reason |
|---|---|---|
| `java/spring-disabled-csrf-protection` (`SecurityConfig`) | **Won't fix: not applicable** | The API is stateless and authenticated only by a `Bearer` header. Browsers never attach that header on their own, so a forged cross-site request carries no credentials. No API endpoint reads a cookie. Keycloak's own pages carry their own CSRF protection. |
| `java/unreleased-lock` (`InMemoryShiftLockProvider`) | **False positive** | Every acquired lock is pushed onto `held` immediately and released in `finally`, including when a later key times out (covered by `timesOutWhileAnotherThreadHoldsTheKey`). CodeQL expects the unlock in a `finally` directly after each `lock()` and cannot follow the collection. This provider is used only for local single-JVM experiments. |

## Checklist with evidence

### A01 Broken Access Control
- **Deny by default:** every path outside the listed rules is `denyAll()`. Role rules on the URL run *before* body validation, so a wrong role gets `403` without learning the input rules (SG-65). `@PreAuthorize` is a second layer. Evidence: `SecurityConfig`, `AuthorizationOrderTests`, and the `isForbidden()` cases in the API tests of every module.
- **Ownership:** cancelling someone else's registration answers `404 REGISTRATION_NOT_FOUND`, so it does not even reveal that the registration exists. Evidence: `CancelRegistrationApiTests.onlyOwnerCanCancel`.
- **No IDOR on "me" endpoints:** identity comes from the token's `sub`, never from a parameter.
- **Internal endpoints:** `/actuator/**` and `/auth/admin/` answer `404` at the edge (checked by `smoke-test.mjs`). Swagger UI is not routed by the edge.
- **CORS:** one explicit origin, only for `/api/**`. No wildcard; credentials are not allowed.

### A02 Security Misconfiguration
- Headers: CSP (S-1), `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`, `Permissions-Policy`, `Cross-Origin-Opener-Policy`, and HSTS (one year) at the TLS edge.
- `server_tokens off`. Errors are RFC 7807 problems without stack traces. The production realm disables registration and direct password grants. The dev-only password client does not exist in production (checked by `smoke-test.mjs`).
- Containers run as non-root, have memory limits, and apply `apk upgrade` for fixes released after the base image.

### A03 Software Supply Chain Failures
- `package-lock.json` with `npm ci`, the Maven wrapper, and pinned image tags (one exception: base images follow minor tags, scanned by Trivy).
- New dependencies are pinned to releases at least two weeks old (a project rule; Playwright 1.65.0 rather than 1.65.1).
- Dependabot covers Maven, npm, GitHub Actions, Docker and Compose.
- Trivy fails CI on HIGH/CRITICAL misconfigurations in Dockerfiles and Compose, and on fixable CRITICAL vulnerabilities in images; fixable HIGH ones are reported to the Security tab (SARIF). CD runs the same image gate before deploying. CodeQL runs for Java and TypeScript.
- CD publishes images with an SBOM and build provenance (GHCR).

### A04 Cryptographic Failures
- TLS 1.2/1.3 at the edge with Let's Encrypt, plus HSTS. Internal traffic stays on the private Compose network.
- JWTs are RS256, checked for signature, issuer and audience (`shiftgrab-api`). Tokens live 5 minutes.
- Secrets come only from environment variables (`.env.prod`, mode 600, git-ignored) and masked CI secrets. No real secret has ever been committed: `.env*` is ignored apart from the two example files.
- Passwords are stored by Keycloak (PBKDF2), never by the application.

### A05 Injection
- SQL: `JdbcClient` with named parameters everywhere. Concatenation is used only for constant fragments (column lists, a choice between two fixed `ORDER BY` clauses).
- CSV/XLSX: formula injection neutralised (a cell starting with `=`, `+`, `-`, `@`, tab or CR gets a `'` prefix, `ReportCsv`). XLSX writes typed numeric cells.
- E-mail: plain text, and the subject is MIME-encoded. Names cannot contain control characters (S-5).
- Logs: S-6. Front end: Angular escapes interpolation; the app has no `innerHTML` or `bypassSecurityTrust*`; the CSP is a backstop.

### A06 Insecure Design
- **Overbooking is defended in three layers** (ADR-0005): lock, conditional UPDATE, database constraints. They are proven under load: 1000 users, 0 overbooking (SG-80/81).
- Business rules are enforced server-side (BR-01..BR-11), with the client only as convenience.
- Decisions are recorded as ADRs, including the SG-81 revision with its measured trade-offs.

### A07 Authentication Failures
- Authorization code + PKCE (S256) for a public client. No implicit flow; no password grant in production.
- Keycloak brute-force protection (5 failures), plus the per-address limit at the edge (S-3) and the password policy (S-4).
- The session is 30 minutes idle; tokens are refreshed only while the session is valid. Logout ends the Keycloak session.

### A08 Software or Data Integrity Failures
- The audit log is append-only, enforced by a database trigger (V4), and written in the same transaction as the change.
- E-mails and live updates go through a transactional outbox: no lost or phantom side effects (ADR-0007).
- Images carry provenance attestations; deployment uses exact image tags; rollback restores the previous tag.

### A09 Security Logging and Alerting Failures
- Business changes are audited with actor, action, target and a before/after summary.
- Prometheus alerts: instance down, 5xx above 5 %, claim p95, claims refused because Redis is down.
- **Gap (accepted):** Keycloak login failures are not alerted on. See below.

### A10 Mishandling of Exceptional Conditions
- Redis is down → claims fail closed (`503 LOCK_UNAVAILABLE`); everything else keeps working.
- Timeouts, deadlocks and pool exhaustion → `503` with `Retry-After`, never a half-written claim, because the transaction rolls back.
- Unknown exceptions → a generic `500 INTERNAL_ERROR`, logged with its cause, no details to the client.

## Accepted risks / later work

| Risk | Why accepted now | Next step |
|---|---|---|
| `style-src 'unsafe-inline'` | Angular injects component styles at runtime. Styles cannot execute script, and `script-src` stays strict | Angular CSP nonce (`ngCspNonce`) once nginx can inject a per-request nonce |
| No alert on login-failure spikes | Keycloak records the events, but they are not exported to Prometheus | Keycloak metrics SPI or event listener → alert rule |
| Rate limit keyed by address | Users behind one NAT share a budget (30/min, burst 20) | Revisit with real traffic numbers |
| Backups on the same server | No VPS yet | Off-site copy (runbook §6) |
| No independent penetration test | Portfolio project | An OWASP ZAP baseline scan in CI is a cheap first step |
