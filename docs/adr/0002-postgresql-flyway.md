# ADR-0002: PostgreSQL with Flyway, replacing SQLite

- **Status:** Accepted
- **Date:** 2026-11-16

## Context
The initial prototype used SQLite locally and declared PostgreSQL only for the `prod` profile (without the driver). Two databases mean SQL that works in development can fail in production, and SQLite serialises all writes with a file lock, so concurrency behaviour in development says nothing about production. BR-03 (no overlapping shifts) needs range types and exclusion constraints.

## Options considered
1. **Keep SQLite for dev, PostgreSQL for prod.** Zero-setup dev, but dev/prod parity is lost and concurrency tests are meaningless.
2. **MySQL.** Widely used, but no range types or exclusion constraints; BR-03 would have to rely on application code only.
3. **PostgreSQL everywhere** (Docker Compose in dev, Testcontainers in tests) with **Flyway** migrations.

## Decision
Option 3. PostgreSQL 16 in every environment. Schema is owned by versioned Flyway migrations (`V1__init.sql`, …); Hibernate runs with `ddl-auto: validate`. Uses `TIMESTAMPTZ`, `tstzrange`, the `btree_gist` extension and partial indexes.

## Consequences
- ➕ Dev/test/prod parity; concurrency tests exercise the real database.
- ➕ BR-03 enforced by `EXCLUDE USING gist`, BR-02 by a partial unique index.
- ➕ Every schema change is reviewed, versioned and repeatable.
- ➖ Developers need Docker running locally.
- ➖ Schema is PostgreSQL-specific (accepted: no plan to change databases).

## 🇻🇳 Tóm tắt
Bỏ SQLite, dùng **PostgreSQL ở mọi môi trường** (Docker Compose khi dev, Testcontainers khi test) và quản lý schema bằng **Flyway**. Lý do: SQLite khóa cả file khi ghi nên test đồng thời ở dev vô nghĩa, và dev/prod khác database dễ sinh lỗi. PostgreSQL có range type và exclusion constraint, cho phép database tự chặn ca trùng giờ (BR-03). Đánh đổi: máy dev phải chạy Docker.
