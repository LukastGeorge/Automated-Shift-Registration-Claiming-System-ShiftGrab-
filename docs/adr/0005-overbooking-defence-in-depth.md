# ADR-0005: Three layers of defence against overbooking

- **Status:** Accepted
- **Date:** 2026-11-16

## Context
NFR-01 requires zero overbooking and zero overlapping shifts under any concurrency. Any single mechanism can fail: a distributed lock can expire during a GC pause or Redis failover; application code can have bugs; someone can run SQL by hand.

## Options considered
1. **Lock only.** Simple, but correctness depends on lock timing — unsafe by design.
2. **Database only** (atomic update + constraints). Correct, but under flash-sale load every request hits the database and BR-04 still cannot be checked atomically.
3. **Defence in depth:** lock + atomic conditional update + database constraints.

## Decision
Option 3:
1. **Distributed lock** (ADR-0004) — serialises rule checks and smooths load.
2. **Atomic conditional update** — `UPDATE shifts SET booked_slots = booked_slots + 1 WHERE id = ? AND booked_slots < max_slots AND status = 'OPEN'`; zero updated rows means `SHIFT_FULL`. Manager edits use `@Version` optimistic locking (SG-21).
3. **Constraints** — `CHECK (booked_slots BETWEEN 0 AND max_slots)`, partial `UNIQUE (shift_id, user_id) WHERE status='CONFIRMED'`, `EXCLUDE USING gist (user_id WITH =, time_range WITH &&) WHERE status='CONFIRMED'`.

Constraint violations are translated to domain error codes (`SHIFT_FULL`, `ALREADY_REGISTERED`, `SHIFT_OVERLAP`) by the exception handler.

## Verification (SG-34)
| Test | Setup | Result |
|---|---|---|
| `MultiInstanceClaimTests` — AC7 | **Two backend instances** (two Spring applications on two HTTP ports) sharing PostgreSQL and Redis; 100 staff, 5 slots, requests released together and split across instances | exactly 5 × `201`, 95 × `SHIFT_FULL`; DB: `FULL`, `booked_slots = 5`, 5 confirmed rows |
| `MultiInstanceClaimTests` — BR-04 | One staff member fires 10 claims of 8 h in one week, split across both instances | exactly 5 succeed (40 h); shows why layer 1 is needed for multi-row rules |
| `DefenceLayerTests` — layer 2 alone | 50 unlocked transactions call `tryReserveSlot` on a 5-slot shift | exactly 5 succeed |
| `DefenceLayerTests` — layer 3 alone | 20 concurrent raw inserts of the same registration / of 20 overlapping shifts | exactly 1 row each |
| `ClaimApiTests` — layer 2 | status left `OPEN` while `booked_slots = max_slots` | `SHIFT_FULL`, no row inserted |

The multi-instance test uses real signed JWTs (HMAC, test-only decoder) so 100 distinct users can call the API over HTTP without Keycloak. It found a real bug: parallel first requests of a new user could fail on `uq_users_email` during profile provisioning (fixed with one retry).

**Flash-sale fast path:** a claim on a shift that is already `FULL` is rejected before taking any lock. It can only reject, never grant, so a stale read is harmless, and late requests no longer queue on the shift lock.

## Consequences
- ➕ Correctness does not depend on any single component; each layer is independently testable.
- ➕ Strong, demonstrable story: concurrency tests (100 threads vs 5 slots) and k6 load tests prove zero overbooking.
- ➖ More code paths to test (each layer is tested with the others disabled).
- ➖ `time_range` duplicates shift times in `shift_registrations` (kept consistent by BR-09).

## 🇻🇳 Tóm tắt
Áp dụng **phòng thủ nhiều lớp**: (1) khóa phân tán để tuần tự hóa kiểm tra quy tắc, (2) câu `UPDATE ... WHERE booked_slots < max_slots` nguyên tử, (3) ràng buộc database (`CHECK`, partial `UNIQUE`, `EXCLUDE`). Mỗi lớp có thể hỏng riêng lẻ (lock hết hạn, bug code, SQL tay), nhưng cả ba cùng hỏng thì gần như không thể. Mỗi lớp được test độc lập, và kết quả được chứng minh bằng test đồng thời cùng test tải k6.
