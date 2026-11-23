# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [0.7.0] - 2026-11-23 — Sprint 7: Docker, CD and operations

### Added
- Production Dockerfiles: multi-stage, JRE-only/unprivileged nginx runtimes, Spring Boot layers, non-root users, health checks; web app configured at start-up from environment variables ([#34](../../issues/34), [#108](../../pull/108)).
- Production Compose stack: Nginx edge (TLS with Let's Encrypt, least-connection balancing over two backends, WebSocket, `/auth` to Keycloak, internal endpoints hidden), Keycloak in production mode with its own database, memory limits, log rotation; end-to-end smoke test and production runbook ([#35](../../issues/35), [#109](../../pull/109)).
- Monitoring: claim outcome counter and latency histograms, Prometheus with four alert rules, Grafana dashboard provisioned from files ([#36](../../issues/36), [#110](../../pull/110)).
- Security scanning: CodeQL for Java and TypeScript, Trivy for Dockerfiles and images on every PR and weekly; required checks on `main` and `develop` ([#37](../../issues/37), [#111](../../pull/111)).
- Continuous deployment: images to GHCR with SBOM and provenance, rolling deployment, smoke test and automatic rollback ([#39](../../issues/39), [#112](../../pull/112)).
- Daily backups of both databases with a restore script, tested by a restore drill ([#40](../../issues/40), [#113](../../pull/113)).
- VPS provisioning and hardening script; infrastructure CI with ShellCheck and configuration validation ([#38](../../issues/38), [#114](../../pull/114)).

### Fixed
- Runtime images apply Alpine security updates (a fixable CRITICAL in the nginx base image, found by Trivy).

## [0.6.0] - 2026-11-23 — Sprint 6: Reports and audit screens

### Added
- Hours and pay report per staff member for a period: ended shifts only, a shift counts wholly on its start day (organisation zone), staff without shifts listed with zeros, pay at the snapshotted rate (BR-10), totals row ([#31](../../issues/31), [#103](../../pull/103)).
- Report export as CSV (UTF-8 BOM for Excel, formula injection neutralised) and XLSX (numeric cells with formats) in Vietnamese or English ([#32](../../issues/32), [#104](../../pull/104)).
- Manager screens: report with downloads named by the server, audit log with actions in words, change summaries and filters by action, days and author ([#33](../../issues/33), [#105](../../pull/105)).

### Changed
- CORS exposes `Content-Disposition` so the web app can read download file names.
- Migration V7: index for the report; removes two `audit_log` indexes that duplicated V1's.

## [0.5.0] - 2026-11-22 — Sprint 5: Real-time, notifications and audit

### Added
- Live slot updates: STOMP over WebSocket, token checked on CONNECT, read-only channel; Redis pub/sub fans updates out across backend instances; newest update per shift wins (database clock); live indicator and "new shifts" notice in the web app ([#27](../../issues/27), [#100](../../pull/100)).
- E-mail notifications (Vietnamese and English) after a claim, a cancellation and a shift cancelled by the manager, through a transactional outbox (Spring Modulith event publication registry): a mail outage never rolls back a claim and no e-mail is lost ([ADR-0007](docs/adr/0007-transactional-outbox-for-side-effects.md), [#28](../../issues/28), [#98](../../pull/98)).
- Opening reminders 30 minutes before registration opens, opt-in from the profile page, one digest per person, sent exactly once across instances (ShedLock + database marker + outbox) ([#29](../../issues/29), [#99](../../pull/99)).
- Append-only audit log of every business change, written in the same transaction, with `GET /api/v1/audit-log` filters ([#30](../../issues/30), [#96](../../pull/96)).
- Scheduled jobs with ShedLock; resubmission of failed event publications.

### Changed
- Dependabot: TypeScript moves only with Angular; npm dependency updates ([#92](../../pull/92)–[#94](../../pull/94), [#97](../../pull/97)).

### Fixed
- `backend/mvnw.cmd` stored with CRLF in the index showed as modified on every Windows checkout ([#97](../../pull/97)).

## [0.4.0] - 2026-11-22 — Sprint 4: Web app

### Added
- Angular 22 web app: Keycloak login (Authorization Code + PKCE), role-based navigation and guards, Vietnamese/English at runtime, light/dark theme, runtime `config.json` so one build runs in every environment ([#23](../../issues/23), [#84](../../pull/84)).
- Staff screens: open shifts by day with a live countdown to the opening time, claim with in-place updates and clear messages for every refusal, my shifts with weekly hours, cancel before the deadline ([#24](../../issues/24), [#86](../../pull/86)).
- Manager screens: shifts by date range with create / edit (`If-Match`, only changed fields, locked times once booked) / cancel with a reason; recurring templates with idempotent generation; hourly rates ([#25](../../issues/25), [#87](../../pull/87)).
- Frontend CI: lint, Vitest with a coverage gate (70% lines / 60% branches), production build; Dependabot for npm ([#26](../../issues/26), [#85](../../pull/85)).

### Changed
- Redisson 4.8.0, Maven 3.10.2, Mailpit 1.34.0, `actions/setup-java` v6 ([#78](../../pull/78), [#80](../../pull/80), [#81](../../pull/81), [#83](../../pull/83)).

### Security
- Role-restricted endpoints are authorized in the security filter chain before request validation, so the wrong role gets `403` instead of a `400` that listed the endpoint's input rules. Found by the end-to-end smoke test ([#88](../../issues/88), [#89](../../pull/89)).

## [0.3.0] - 2026-11-22 — Sprint 3: Claiming core and concurrency

### Added
- Distributed lock: `RedisLockProvider` (Redisson) behind `ShiftLockProvider`, ordered keys (user → shift), 3 s wait budget, explicit 10 s lease, fail closed when Redis is down; lock-wait timer metric ([#18](../../issues/18), [#73](../../pull/73)).
- Claim a shift slot with all business rules (BR-01..BR-05): registration window, weekly hour limit split per ISO week, no overlap, atomic slot reservation; flash-sale fast path rejects full shifts before locking ([#19](../../issues/19), [#74](../../pull/74)).
- Cancel a registration before the deadline (BR-06), returning the slot immediately ([#20](../../issues/20), [#75](../../pull/75)).
- My shifts with weekly hours ([#21](../../issues/21), [#76](../../pull/76)).
- Concurrency suite: two backend instances sharing PostgreSQL and Redis, 100 staff vs 5 slots → exactly 5 claims; each defence layer tested alone (ADR-0005) ([#22](../../issues/22), [#77](../../pull/77)).
- Error codes `CLAIM_BUSY` and `LOCK_UNAVAILABLE` (503 with `Retry-After`).

### Changed
- Weekly hour limit (40 h) and cancel deadline (24 h) are deployment settings.
- Dependencies: Redis 8.10.3 image, JaCoCo 0.8.16, Maven 3.10.1, `actions/upload-artifact` v7 ([#66](../../pull/66), [#68](../../pull/68)–[#70](../../pull/70)); Dependabot ignores PostgreSQL major upgrades ([#72](../../pull/72)).

### Fixed
- Parallel first requests of a new user could fail on `uq_users_email` during profile provisioning (one retry).
- Profile provisioning inside a read-only transaction failed; the upsert now runs in its own transaction.

## [0.2.0] - 2026-11-16 — Sprints 1–2: Backend foundation and shift management

### Added
- Keycloak authentication: JWT resource server (signature, issuer, audience), MANAGER/STAFF role mapping, user profile provisioned on first call, `GET /api/v1/me` ([#9](../../issues/9), [#55](../../pull/55)).
- Unified RFC 7807 error contract with a `code` on every error; database constraint names translated to domain codes ([#10](../../issues/10), [#56](../../pull/56)).
- CI quality gate: JaCoCo coverage ≥ 70% lines / 60% branches, coverage summary and report artifact ([#11](../../issues/11), [#57](../../pull/57)).
- Create and read shifts with time rules: start in the future, at most 12 h, opening before start; ETag on reads ([#12](../../issues/12), [#58](../../pull/58)).
- Browse shifts with date/status filters and stable pagination; staff only see actionable shifts ([#16](../../issues/16), [#59](../../pull/59)).
- Edit shifts with `If-Match` optimistic locking; BR-08/BR-09 enforced atomically in SQL ([#13](../../issues/13), [#60](../../pull/60)).
- Cancel shifts together with their registrations in one transaction (BR-07); event with affected staff for notifications ([#14](../../issues/14), [#61](../../pull/61)).
- Recurring shift templates with all-or-nothing, idempotent generation in the organisation time zone, including overnight shifts ([#15](../../issues/15), [#62](../../pull/62)).
- Staff list and hourly rates; past registrations keep their snapshotted rate (BR-10) ([#17](../../issues/17), [#63](../../pull/63)).
- Backend foundation: Spring Boot 4, Spring Modulith module boundaries, PostgreSQL + Flyway, local Docker Compose stack with Keycloak realm ([#7](../../issues/7), [#8](../../issues/8)).

### Changed
- SQLite replaced by PostgreSQL in every environment (ADR-0002).
- Local PostgreSQL published on port 5433 to avoid clashing with a native installation ([#64](../../pull/64)).
- CI actions updated: `actions/checkout` v7, `actions/setup-java` v5 ([#50](../../pull/50), [#52](../../pull/52)).

### Fixed
- A duplicate claim of the same shift was reported as `SHIFT_OVERLAP` instead of `ALREADY_REGISTERED`; the overlap exclusion now only compares different shifts (migration V2).

## [0.1.0] - 2026-11-16 — Sprint 0: Inception

### Added
- Software Requirements Specification (en + vi) with business rules BR-01..BR-11 and user stories ([#3](../../issues/3)).
- System design: C4 context/container/module diagrams, claim sequence, ERD ([#4](../../issues/4)).
- Architecture Decision Records 0001–0006 ([#5](../../issues/5)).
- Git Flow conventions, PR/issue templates, Dependabot, CONTRIBUTING guide ([#1](../../issues/1)).
- Monorepo layout and bilingual README ([#2](../../issues/2)).

### Changed
- CI workflow renamed to `ci-backend.yml`, with path filters and concurrency control.
