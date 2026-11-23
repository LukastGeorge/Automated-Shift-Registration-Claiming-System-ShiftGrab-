# Flash sale — run 01: baseline (before tuning)

> 🇻🇳 **Tóm tắt:** 1000 nhân viên tranh 50 suất cùng lúc. **Đúng tuyệt đối** (đúng 50 suất, 0 overbooking, DB khớp),
> nhưng **chưa đạt hiệu năng**: p95 = 6,1 s (mục tiêu < 300 ms) và 138 request bị 503 `CLAIM_BUSY`.
> Nguyên nhân: người đã xếp hàng chờ khóa ca vẫn phải chờ tới lượt chỉ để biết "hết suất". Xử lý ở SG-81.

| | |
|---|---|
| Date | 2026-11-23 |
| Build | `0.7.0` images (develop `2c43576`), production compose: edge nginx → 2 backends, PostgreSQL 16, Redis, Keycloak |
| Host | one laptop (Docker Desktop, WSL2) running the whole stack **and** k6 — absolute numbers are pessimistic |
| Script | [`flash-sale.js`](../flash-sale.js) — 1000 VUs × 1 claim, 10 shifts × 5 slots, 100 VUs per shift, all at once |

## Results

| Metric | Result | Target | |
|---|---:|---|---|
| Claims succeeded (201) | **50** | exactly 50 | ✅ |
| Answered SHIFT_FULL (409) | 812 | — | |
| Answered 503 `CLAIM_BUSY` | **138** | 0 | ❌ |
| Claim latency median | 2 097 ms | — | |
| Claim latency p90 | 5 218 ms | — | |
| Claim latency **p95** | **6 120 ms** | < 300 ms (NFR-02) | ❌ |
| Claim latency max | 8 660 ms | — | |

### Correctness, checked in the database ([`verify-db.sql`](../verify-db.sql))

```
     name      | max_slots | booked_slots | confirmed_rows | no_overbooking | counter_matches_rows
---------------+-----------+--------------+----------------+----------------+----------------------
 Flash sale 1  |         5 |            5 |              5 | t              | t
 ...  (all 10 shifts identical)
 Flash sale 10 |         5 |            5 |              5 | t              | t

 duplicate_claims
------------------
                0
```

Zero overbooking, zero duplicate claims, and the `booked_slots` counter matches the real rows on every shift.
The three layers of ADR-0005 hold under real contention through two instances.

## Analysis

Every claim takes the `user → shift` locks, then runs its checks in a transaction. With 100 requests per shift
arriving together, all 100 queue on the same shift lock:

1. The first 5 holders take the 5 slots; the shift becomes `FULL`.
2. The other 95 are **already waiting inside `tryLock`**. The lock-free "shift is FULL" fast path
   (`rejectFullShiftWithoutLocking`) ran before they queued, so it cannot help them.
3. They are served one at a time — lock, transaction, read the shift, reject — just to learn "full".
   The last in line waits for ~95 serial critical sections and exceeds the 3 s `waitTime` → `CLAIM_BUSY`.

Latency therefore grows linearly with the queue length, not with the number of free slots. This is the
finding SG-81 tunes.

## Next

SG-81: wait for the lock in short slices and re-check the lock-free FULL state between slices, so queued
requests leave the queue as soon as the shift fills. Re-run with the same script and compare.
