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

## Consequences
- ➕ Correctness does not depend on any single component; each layer is independently testable.
- ➕ Strong, demonstrable story: concurrency tests (100 threads vs 5 slots) and k6 load tests prove zero overbooking.
- ➖ More code paths to test (each layer is tested with the others disabled).
- ➖ `time_range` duplicates shift times in `shift_registrations` (kept consistent by BR-09).

## 🇻🇳 Tóm tắt
Áp dụng **phòng thủ nhiều lớp**: (1) khóa phân tán để tuần tự hóa kiểm tra quy tắc, (2) câu `UPDATE ... WHERE booked_slots < max_slots` nguyên tử, (3) ràng buộc database (`CHECK`, partial `UNIQUE`, `EXCLUDE`). Mỗi lớp có thể hỏng riêng lẻ (lock hết hạn, bug code, SQL tay), nhưng cả ba cùng hỏng thì gần như không thể. Mỗi lớp được test độc lập, và kết quả được chứng minh bằng test đồng thời cùng test tải k6.
