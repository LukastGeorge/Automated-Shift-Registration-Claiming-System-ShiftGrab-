# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

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
