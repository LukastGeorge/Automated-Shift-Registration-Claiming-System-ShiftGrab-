# Architecture Decision Records

An ADR captures one significant decision: the context, the options considered, the choice and its consequences. ADRs are immutable once accepted; a changed decision gets a new ADR that supersedes the old one.

🇻🇳 Mỗi ADR ghi lại một quyết định kiến trúc quan trọng: bối cảnh, các phương án đã cân nhắc, lựa chọn và hệ quả. ADR đã chấp nhận thì không sửa; nếu đổi quyết định thì viết ADR mới thay thế ADR cũ.

| # | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith instead of microservices | Accepted |
| [0002](0002-postgresql-flyway.md) | PostgreSQL with Flyway, replacing SQLite | Accepted |
| [0003](0003-keycloak-identity.md) | Keycloak as identity provider | Accepted |
| [0004](0004-redis-distributed-lock.md) | Redis (Redisson) distributed lock behind `ShiftLockProvider` | Accepted |
| [0005](0005-overbooking-defence-in-depth.md) | Three layers of defence against overbooking | Accepted |
| [0006](0006-vps-docker-compose.md) | Single VPS with Docker Compose and GitHub Actions CD | Accepted |

Template: [template.md](template.md)
