# ShiftGrab — Automated Shift Registration & Claiming System

> 🇻🇳 [Đọc bản tiếng Việt](README.vi.md)

[![CI - Backend](https://github.com/LukastGeorge/Automated-Shift-Registration-Claiming-System-ShiftGrab-/actions/workflows/ci-backend.yml/badge.svg?branch=develop)](https://github.com/LukastGeorge/Automated-Shift-Registration-Claiming-System-ShiftGrab-/actions/workflows/ci-backend.yml)

ShiftGrab lets managers publish work shifts with a limited number of slots and lets staff **claim them at a fixed opening time** — a "flash sale" for shifts. The core engineering problem is **correctness under contention**: when hundreds of people click *Claim* at the same second, no shift may ever be overbooked, no one may hold two overlapping shifts, and weekly hour limits must hold.

> **Status:** 🚧 Sprint 0 — inception. Requirements, architecture and decisions are being documented; feature code starts in Sprint 1. See the [roadmap](#roadmap).

## Planned architecture

| Layer | Technology |
|---|---|
| Frontend | Angular (TypeScript, Angular Material), OIDC login, live updates over WebSocket |
| Backend | Java 21, Spring Boot 3, modular monolith (Spring Modulith) |
| Identity | Keycloak (OAuth2 / OpenID Connect), roles `MANAGER` / `STAFF` |
| Data | PostgreSQL 16 + Flyway migrations |
| Concurrency | Redis distributed lock (Redisson) + atomic SQL update + database constraints |
| Observability | Spring Actuator, Micrometer, Prometheus, Grafana |
| Delivery | Docker, Docker Compose, Nginx (TLS), GitHub Actions CI/CD → GHCR → VPS |
| Quality | JUnit 5, Testcontainers, concurrency tests, JaCoCo, k6 load tests |

Detailed design: [`docs/design`](docs/design) · Decisions: [`docs/adr`](docs/adr) · Requirements: [`docs/srs`](docs/srs)

## Repository layout

```
backend/      Spring Boot application
frontend/     Angular application
infra/        Docker Compose, Keycloak realm, Nginx, Prometheus, Grafana, scripts
load-tests/   k6 scenarios and results
docs/         SRS, design (C4, ERD, sequences), ADRs, runbook
```

## Roadmap

| Sprint | Goal |
|---|---|
| 0 | Inception: repo structure, SRS, design, ADRs, backlog |
| 1 | Backend foundation: PostgreSQL, Flyway, Keycloak, CI with Testcontainers |
| 2 | Shift management (CRUD, recurring shifts) |
| 3 | Claim / cancel with distributed locking and business rules |
| 4 | Angular foundation |
| 5 | Real-time updates, e-mail notifications, audit log |
| 6 | Reports (hours & pay, CSV/Excel) |
| 7 | Docker production stack, monitoring, CD to VPS with HTTPS |
| 8 | Load testing (k6) and tuning |
| 9 | Hardening, documentation, release v1.0.0 |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the branching model, commit conventions and Definition of Done.
