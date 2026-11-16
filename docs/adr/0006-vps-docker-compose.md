# ADR-0006: Single VPS with Docker Compose and GitHub Actions CD

- **Status:** Accepted
- **Date:** 2026-11-16

## Context
The system must be publicly reachable for recruiters to try, cost little, and demonstrate DevOps skills (containerisation, CI/CD, TLS, monitoring, backups). It consists of ~9 containers (Nginx, frontend, 2× backend, PostgreSQL, Redis, Keycloak, Prometheus, Grafana).

## Options considered
1. **PaaS (Render, Railway).** Easiest, free tiers, but hides most operations work and free tiers sleep or limit RAM (Keycloak won't fit).
2. **Kubernetes (managed or k3s).** Industry relevant, but control-plane overhead and complexity far exceed the needs of 9 containers on one host.
3. **AWS (EC2 + RDS + ECR).** Strong CV value, but costs can grow unexpectedly and setup time is high.
4. **One VPS (4 GB RAM) + Docker Compose + Nginx + Let's Encrypt**, deployed by GitHub Actions: build images → push to GHCR → SSH → `docker compose pull && up -d` → health check.

## Decision
Option 4. Images are tagged with the git SHA and SemVer tag; the VPS only pulls images (never builds). Secrets live in GitHub Actions secrets and an `.env` file on the server (never in git). The server is hardened: non-root deploy user, SSH keys only, `ufw`, `fail2ban`. Daily `pg_dump` backup with 7-day retention (NFR-06).

## Consequences
- ➕ ~20–24 USD/month, full control, every operational step is visible and documented in the runbook.
- ➕ Same Compose files for local and production (dev/prod parity).
- ➖ Single host = single point of failure; acceptable for v1 (NFR-05).
- ➖ Deployment has brief downtime while containers restart.
- ➕ Migration path: the Compose services map directly to Kubernetes Deployments if needed later.

## 🇻🇳 Tóm tắt
Triển khai trên **một VPS 4 GB bằng Docker Compose**, có Nginx + Let's Encrypt cho HTTPS. GitHub Actions build image, đẩy lên GHCR, SSH vào VPS để pull và khởi động lại, rồi kiểm tra health. Chọn phương án này vì rẻ (khoảng 20–24 USD/tháng), thể hiện được trọn vẹn kỹ năng vận hành, và đơn giản hơn nhiều so với Kubernetes hay AWS. Đánh đổi: một máy chủ duy nhất là điểm lỗi đơn lẻ, và mỗi lần deploy gián đoạn ngắn; v1 chấp nhận được.
