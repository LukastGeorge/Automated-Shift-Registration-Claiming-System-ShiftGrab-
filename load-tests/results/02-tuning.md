# Flash sale — SG-81 tuning (runs 02–10)

> 🇻🇳 **Tóm tắt:** từ kết quả nền (p95 6,1 s, 138 lỗi 503), sau 9 lần đo:
> - **0 lỗi**, vẫn đúng 50 suất, 0 overbooking.
> - Thời gian chờ pool DB giảm từ **233 ms xuống ~1 ms**.
> - Phía server, lượt giành ca chậm nhất **≤ 1,3 s**.
>
> Thay đổi quyết định là **bỏ khóa Redis theo ca, chỉ khóa theo user** (sửa đổi ADR-0004), kèm `SET LOCAL lock_timeout`, ngân sách pool 25/backend và sửa race "manager đổi giờ".
>
> Hạn chế đo: p95 phía client dao động 1,2–3,6 s giữa các lần chạy, vì k6 và 13 container dùng chung 12 core của một laptop. Số liệu phía server mới là tín hiệu ổn định. Mục tiêu 300 ms (NFR-02) cần đo trên VPS.

Same scenario as [run 01](01-baseline.md): 1000 staff, 10 shifts × 5 slots, everybody at the same instant, through nginx and two backends. Every run was on a **fresh stack**, and every run passed the correctness check in the database ([`verify-db.sql`](../verify-db.sql)): exactly 50 claims, `booked_slots` equal to the confirmed rows, 0 duplicates.

## All runs

Times are k6 client-side claim latency.

| Run | Change (cumulative unless noted) | p50 | p95 | p99 | Not 201/409 |
|---|---|---:|---:|---:|---:|
| 01 | baseline: lock `user → shift`, pool 10 | 2 097 ms | 6 120 ms | 7 609 ms | 138 × 503 |
| 02 | wait for the shift lock in 50 ms slices, re-reading "full?" from the DB between slices | 905 ms | 7 715 ms | 9 433 ms | 6 × 503 |
| 03 | + staff open the app before the sale (GET `/me`, realistic) | 2 654 ms | 5 212 ms | 5 989 ms | 53 × 503 |
| 04 | + per-instance sold-out memo | 2 754 ms | **9 330 ms** | 12 354 ms | 162 × 503 |
| 05 | **lock the user only**, no shift lock (pool still 10) | 1 652 ms | 5 952 ms | 6 991 ms | **0** |
| 06 | + pool 25 per backend; 60 s settle before measuring | 192 ms | 2 216 ms | 2 846 ms | 0 |
| 07 | + JVM warm-up before the sale (200 claims on another shift) | 389 ms | 1 251 ms | 1 778 ms | 0 |
| 08 | same as 07 with **500** users (capacity check) | 648 ms | 1 751 ms | 2 196 ms | 0 |
| **09** | **final:** version check + `RETURNING`, `SET LOCAL lock_timeout 1 s`, Hikari timeout 1 s, memo | **103 ms** | 3 557 ms | 3 954 ms | **0** |
| 10 | final with `lock_timeout` **200 ms** | 302 ms | 1 807 ms | 2 381 ms | 30 × 503, 3 × 500 |

### Server side, run 09 (final) vs run 03

Prometheus metrics, per backend.

| | run 03 | run 09 |
|---|---:|---:|
| Pool wait, average (`hikaricp_connections_acquire`) | 233 ms | **≈ 1 ms** |
| Pool wait, max | 3.3 s | 0.24 s |
| Claim request, max (`http_server_requests`) | 6.4 s | **1.26 s** |
| `CLAIM_BUSY` | 53 | **0** |

## What each step taught

1. **Slicing the lock wait (02) and a memo (04) treated the symptom.**
   - Queued requests left earlier, but every slice re-read the shift through a pool of 10 connections.
   - About 300 waiters polling every 50 ms made the pool the queue. 04 was the worst run.
   - The memo itself was sound; it was blocked behind the real problem.
2. **The shift lock was the real problem (05).**
   - Each waiter on `shift:{id}` still ran a full transaction on its turn, one after another, just to learn "full".
   - The conditional `UPDATE` already guarantees no overbooking (ADR-0005), so the shift lock only made claims wait in line.
   - Dropping it removed every `CLAIM_BUSY` at once. The pool then became the visible bottleneck (average wait 365 ms).
3. **The pool size followed from the measurement (06).**
   - 25 per backend fits the connection budget: 2 × 25 + Keycloak 20 + reserve = 75 < 100.
   - It cut p50 tenfold.
4. **Cold JVMs inflate a single burst (07).**
   - Fresh containers paid class loading and JIT during the sale; one run showed a 0.34 s "Metadata GC Threshold" pause.
   - Production servers are warm, so the script now warms up on a separate shift before the sale.
5. **Half the load was not faster (08).** On this laptop, run-to-run noise (±50 %) is larger than the effect of small changes, so only large effects are trusted.
6. **`lock_timeout` 200 ms vs 1 s (09 vs 10).**
   - 200 ms answered 30 claims with "retry" while slots were still being filled; these are definite answers lost.
   - It also exposed a bug: a pool timeout while *opening* a transaction surfaced as **500**. Now fixed: any Hikari timeout is `503 SERVICE_BUSY` + `Retry-After`, covered by `PoolExhaustionHandlingTests`.
   - **1 s is the setting**: 0 errors.

## Where the remaining client-side time goes

In run 09 the server answered every claim within 1.26 s, with about 1 ms pool waits, yet k6 measured a cluster at about 3.4 s (p90 and above).

- **Ruled out:**
  - TCP listen-queue overflows: `ListenOverflows = 0` on the edge and both backends.
  - Pool exhaustion: 0 `SERVICE_BUSY`.
- **What remains:** time before Spring's timer starts, i.e. queued for a Tomcat worker thread, plus k6's own scheduling. During the burst, k6 used 4.3 cores and each backend 4 cores, on a 12-core laptop that also ran PostgreSQL, Keycloak, Redis and Grafana.
- **Conclusion:** not trustworthy as an absolute number on this hardware.

## Against the targets

| Target | Result |
|---|---|
| Exactly the available slots succeed, 0 overbooking (NFR-01) | ✅ every run, verified in the database |
| Flash-sale burst: 0 errors | ✅ run 09 |
| Flash-sale burst: p95 < 2 s | ⚠️ server-side yes (max 1.26 s); client-side 1.2–3.6 s on this laptop. Re-measure with k6 on a separate machine |
| NFR-02 steady load: p95 < 300 ms at 500 req/s on the VPS | ⏳ not measurable without the VPS. Run `flash-sale.js` with a `constant-arrival-rate` scenario there |

## Reproduce

See [the load-test README](../README.md). The runs used `grafana/k6:2.3.0`, PostgreSQL 16.16, Redis 8.10, Keycloak 26.8, images built from the SG-81 branch, Docker Desktop on Windows 11 (12 cores, 8 GB for Docker).
