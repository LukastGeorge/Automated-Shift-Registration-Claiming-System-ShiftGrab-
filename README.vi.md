# ShiftGrab — Hệ thống Đăng ký & Giành ca làm việc tự động

> 🇬🇧 [English version](README.md)

ShiftGrab cho phép quản lý đăng các ca làm việc có số suất giới hạn, và nhân viên **giành ca tại một thời điểm mở đăng ký cố định**, giống như một đợt "flash sale" cho ca làm. Bài toán kỹ thuật cốt lõi là **đảm bảo tính đúng đắn khi tranh chấp đồng thời**: khi hàng trăm người cùng bấm *Giành ca* trong một giây, không ca nào được phép vượt số suất, không ai giữ hai ca trùng giờ, và hạn mức giờ/tuần luôn được tôn trọng.

> **Trạng thái:** 🚧 Sprint 0: khởi tạo. Đang hoàn thiện tài liệu yêu cầu, kiến trúc và các quyết định kỹ thuật; code tính năng bắt đầu từ Sprint 1. Xem [lộ trình](#lộ-trình).

## Kiến trúc dự kiến

| Tầng | Công nghệ |
|---|---|
| Frontend | Angular (TypeScript, Angular Material), đăng nhập OIDC, cập nhật real-time qua WebSocket |
| Backend | Java 21, Spring Boot 3, modular monolith (Spring Modulith) |
| Định danh | Keycloak (OAuth2 / OpenID Connect), vai trò `MANAGER` / `STAFF` |
| Dữ liệu | PostgreSQL 16 + Flyway migration |
| Đồng thời | Khóa phân tán Redis (Redisson) + câu lệnh SQL nguyên tử + ràng buộc database |
| Giám sát | Spring Actuator, Micrometer, Prometheus, Grafana |
| Triển khai | Docker, Docker Compose, Nginx (TLS), GitHub Actions CI/CD → GHCR → VPS |
| Chất lượng | JUnit 5, Testcontainers, test đồng thời, JaCoCo, test tải k6 |

Thiết kế chi tiết: [`docs/design`](docs/design) · Quyết định kiến trúc: [`docs/adr`](docs/adr) · Yêu cầu: [`docs/srs`](docs/srs)

## Cấu trúc repo

```
backend/      Ứng dụng Spring Boot
frontend/     Ứng dụng Angular
infra/        Docker Compose, realm Keycloak, Nginx, Prometheus, Grafana, script
load-tests/   Kịch bản và kết quả k6
docs/         SRS, thiết kế (C4, ERD, sequence), ADR, runbook
```

## Lộ trình

| Sprint | Mục tiêu |
|---|---|
| 0 | Khởi tạo: cấu trúc repo, SRS, thiết kế, ADR, backlog |
| 1 | Nền tảng backend: PostgreSQL, Flyway, Keycloak, CI với Testcontainers |
| 2 | Quản lý ca (CRUD, ca lặp lại) |
| 3 | Giành/hủy ca với khóa phân tán và quy tắc nghiệp vụ |
| 4 | Nền tảng Angular |
| 5 | Real-time, thông báo email, audit log |
| 6 | Báo cáo (giờ công & lương, CSV/Excel) |
| 7 | Docker production, giám sát, CD lên VPS có HTTPS |
| 8 | Test tải (k6) và tối ưu |
| 9 | Hoàn thiện, tài liệu, phát hành v1.0.0 |

## Đóng góp

Xem [CONTRIBUTING.md](CONTRIBUTING.md) để biết mô hình nhánh, quy ước commit và Definition of Done.
