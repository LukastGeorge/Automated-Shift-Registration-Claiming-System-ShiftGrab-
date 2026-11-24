# ADR-0004: Redis (Redisson) distributed lock behind `ShiftLockProvider`

- **Status:** Accepted, amended 2026-11-23 by SG-81 (see [Revision](#revision-sg-81-2026-11-23-lock-the-user-not-the-shift))
- **Date:** 2026-11-16

## Context
The prototype's `InMemoryShiftLockProvider` uses a `ReentrantLock` per shift. It only works inside one JVM; with two backend instances (NFR-03) two claims can run the business-rule checks in parallel. BR-04 (weekly hours) reads several rows and cannot be expressed as one atomic SQL statement, so it needs mutual exclusion per user across instances.

## Options considered
1. **Pessimistic DB lock** (`SELECT ... FOR UPDATE` on shift and user rows). No extra infrastructure, but hot rows hold DB connections while waiting; at flash-sale peaks the connection pool is exhausted.
2. **Optimistic locking only** (`@Version` + retry). No waiting, but under heavy contention almost every claim fails and retries (retry storm).
3. **Redis lock via Redisson** (`RLock`, with watchdog and lease time). Waiting happens outside the database; locks are fair and have automatic expiry if an instance dies.

## Decision
Option 3, implemented as `RedisLockProvider` behind the existing `ShiftLockProvider` interface (Strategy pattern from the prototype). `InMemoryShiftLockProvider` remains for unit tests. Locks are acquired in a fixed order — `user:{id}` then `shift:{id}` — with `waitTime = 3 s`, `leaseTime = 10 s`. Timeouts return `503` with `Retry-After`.

### Implementation notes (SG-33)
- `ShiftLockProvider.executeWithLocks(keys, task)` acquires keys in the given order with **one overall wait budget** (3 s for all keys together) and releases them in reverse order, also when the task throws or a later key times out.
- Leases are explicit (10 s, no Redisson watchdog), so a crashed instance cannot hold a lock forever.
- **Fail closed** (decision of 2026-11-16): if Redis is unreachable the claim is refused with `503 LOCK_UNAVAILABLE` rather than checking BR-04 without mutual exclusion. Redisson connects lazily, so the rest of the application starts and works without Redis.
- Timer `shiftgrab.lock.wait{result=acquired|timeout|error}`.
- `shiftgrab.lock.provider=in-memory` selects the single-JVM implementation (local experiments only).

## Consequences
- ➕ Correct rule checks across instances; DB connections are not held while waiting.
- ➕ Strategy interface keeps business code independent of the lock technology.
- ➖ Redis becomes a dependency on the claim path. A lock is **not** a correctness guarantee (lease expiry, failover), which is why ADR-0005 adds database-level protection.
- ➖ Lock wait time must be monitored (`shiftgrab.lock.wait` metric).

## Revision (SG-81, 2026-11-23): lock the user, not the shift

### What the measurements showed
The k6 flash sale (1000 staff, 10 shifts × 5 slots, two instances; [`load-tests/results`](../../load-tests/results/)) contradicted one assumption above. Queueing on `shift:{id}` did not keep database connections free: every waiter still opened a full claim transaction when its turn came, only to learn the shift was full, one after another. Baseline: p95 **6.1 s** and **138** `CLAIM_BUSY`. Each waiting holder also competed with the crowd for the 10 pooled connections (average pool wait 233 ms).

Option 1's risk ("hot rows hold DB connections while waiting") is real, but it can be bounded. The Redis queue's cost could not be bounded.

### Decision
- The claim path locks **`user:{id}` only**. That lock still guards one person's multi-row rules across instances (BR-02 duplicate claims, BR-03 overlap, BR-04 weekly hours). Redis stays on the claim path, and fail-closed is unchanged.
- Claims on the same shift are serialised by PostgreSQL's row lock, taken by the conditional `UPDATE` (ADR-0005 layer 2). That UPDATE was always the overbooking guarantee; the shift lock only made claims wait in line.
- The transaction is kept short and bounded:
  1. Rule checks are reads done **before** the hot-row `UPDATE`.
  2. After the UPDATE, only the registration and its outbox/audit rows are written. No Redis or HTTP call happens inside the transaction.
  3. `lock_timeout = 1 s` and `statement_timeout = 2 s` are set with `set_config(..., true)` (= `SET LOCAL`), so they never leak to the next user of a pooled connection and stay valid behind PgBouncer in transaction mode. A timeout answers `503 CLAIM_BUSY` with `Retry-After: 1`.
- **Race closed at the same time:** the reserving UPDATE now also requires `version = <version read>`. It does not bump the version, because claims are not edits; bumping it would make concurrent claims invalidate each other. The registration copies its times from the row returned by `RETURNING`. Before this, a manager changing the times between a claim's read and its update produced a registration with the old times. If the version changed, the rules are checked once more against the new row. If it changed again, the answer is `409 SHIFT_CHANGED`.
- **Sold-out memo** per instance (TTL 1 s). Late requests learn "full" from memory before opening a transaction. Only the AC6 "already registered?" read remains. It can only refuse, never grant. It is forgotten on cancellation, and on `ShiftChanged` or `ShiftCancelled` after commit.
- **Connection budget**, against `max_connections = 100`: 2 backends × `DB_POOL_SIZE` 25, plus Keycloak `KC_DB_POOL_MAX_SIZE` 20, plus about 5 reserved, gives 75. Hikari `connectionTimeout = 1 s`; pool exhaustion answers `503 SERVICE_BUSY` with `Retry-After`. Beyond about 3 instances, put PgBouncer (transaction mode) in front.
- **Lock timeout chosen by measurement:**
  - 200 ms turned 30 definite answers into "retry" (`CLAIM_BUSY`) while slots were still being filled.
  - 1 s gave 0 errors.

### Consequences
- ➕ 0 `CLAIM_BUSY` at 1000 users, down from 138. Server-side claim time stays at or below 1.3 s, and average pool wait drops from 233 ms to about 1 ms. Correctness is unchanged: exactly 50 claims, 0 overbooking, 0 duplicates, verified in the database.
- ➕ The deadlock surface shrinks: one lock key per claim.
- ➖ During a flash sale, waiting moves into PostgreSQL. It is bounded by `lock_timeout` and the connection budget, and must be watched (`hikaricp_connections_acquire_seconds`, `shiftgrab.claims{result=CLAIM_BUSY}`).
- ➖ Another instance may refuse a slot for up to 1 s after it was freed (sold-out memo). Slots are never granted wrongly.

## 🇻🇳 Tóm tắt
Ban đầu (2026-11-16): thay `ReentrantLock` (chỉ có tác dụng trong một JVM) bằng **khóa phân tán Redisson** sau interface `ShiftLockProvider` (Strategy pattern).
- Khóa theo thứ tự user → ca, chờ tối đa 3 giây, lease 10 giây.
- Chọn Redis thay vì `SELECT FOR UPDATE` với kỳ vọng không giữ kết nối DB khi chờ.

**Sửa đổi SG-81 (2026-11-23), dựa trên số đo k6:**
- Hàng chờ trên khóa *ca* không tiết kiệm được kết nối DB, vì người chờ đến lượt vẫn phải mở transaction để biết "hết suất". Kết quả nền: p95 6,1 s, 138 lỗi 503.
- **Giờ chỉ khóa theo *user*** (BR-02/03/04). Các lượt giành cùng một ca do khóa dòng của PostgreSQL xếp hàng; lệnh `UPDATE` có điều kiện vốn đã là thứ chặn overbooking.
- Transaction được giữ ngắn và có giới hạn:
  - kiểm tra quy tắc trước UPDATE;
  - không gọi I/O ngoài trong transaction;
  - `SET LOCAL lock_timeout = 1 s`, quá hạn thì trả 503 + `Retry-After`;
  - ngân sách kết nối 75/100, Hikari chờ tối đa 1 s.
- Sửa luôn race "manager đổi giờ đúng lúc có người giành": UPDATE kiểm tra `version` nhưng không tăng nó; giờ ca lấy từ `RETURNING`.
- Kết quả: 0 lỗi 503, độ đúng giữ nguyên.
