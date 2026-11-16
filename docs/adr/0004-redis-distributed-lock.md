# ADR-0004: Redis (Redisson) distributed lock behind `ShiftLockProvider`

- **Status:** Accepted
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

## 🇻🇳 Tóm tắt
Thay `ReentrantLock` (chỉ có tác dụng trong một JVM) bằng **khóa phân tán Redisson**, cài đặt sau interface `ShiftLockProvider` có sẵn (Strategy pattern). Khóa theo thứ tự cố định user → ca để tránh deadlock; chờ tối đa 3 giây, lease 10 giây. Chọn Redis thay vì `SELECT FOR UPDATE` vì không giữ kết nối DB khi chờ, và thay vì chỉ dùng optimistic lock vì tránh được bão retry giờ cao điểm. Lock không phải bảo đảm tuyệt đối, nên ADR-0005 bổ sung lớp bảo vệ ở database.
