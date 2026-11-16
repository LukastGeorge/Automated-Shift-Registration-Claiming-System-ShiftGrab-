# ADR-0001: Modular monolith instead of microservices

- **Status:** Accepted
- **Date:** 2026-11-16

## Context
ShiftGrab has seven functional areas (identity, shift, registration, notification, realtime, reporting, audit). It is built by one developer in ~10 weeks and must scale horizontally (NFR-03). The claim operation needs a strongly consistent read-modify-write across shifts, registrations and users.

## Options considered
1. **Microservices** (one service per area + API gateway + message broker). Independent deployment and scaling, but claim would need a distributed transaction or saga across services, plus service discovery, contract testing and far more infrastructure than a 4 GB VPS can host. High risk of doing many things shallowly.
2. **Layered monolith** (`controller/`, `service/`, `repository/` packages). Simple, but nothing stops modules from becoming entangled; boundaries are a convention only.
3. **Modular monolith with Spring Modulith.** One deployable, one database transaction for claim, packages organised by business module, boundaries verified by a test, communication through public APIs and domain events.

## Decision
Option 3. Each module lives in `com.shiftgrab.<module>`; only its root package is public. Side effects use application events delivered after commit. `ApplicationModules.verify()` runs in CI and fails the build on boundary violations or cycles.

## Consequences
- ➕ Claim stays a single ACID transaction; correctness is easier to prove.
- ➕ Scale out by running more identical instances (stateless) — no code change.
- ➕ A module can later be extracted into a service along an existing event boundary.
- ➖ All modules share one release cadence and one database.
- ➖ Requires discipline (enforced by the architecture test) to keep boundaries clean.

## 🇻🇳 Tóm tắt
Chọn **modular monolith** dùng Spring Modulith thay vì microservices. Với một người làm trong khoảng 10 tuần, thao tác giành ca cần một transaction ACID duy nhất; tách microservices sẽ phải dùng saga, gateway và broker, quá nặng cho VPS 4 GB. Ranh giới module được kiểm tra tự động trong CI, scale ngang bằng cách chạy thêm instance. Sau này có thể tách module thành service theo ranh giới event sẵn có.
