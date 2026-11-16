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

## Consequences
- ➕ Correct rule checks across instances; DB connections are not held while waiting.
- ➕ Strategy interface keeps business code independent of the lock technology.
- ➖ Redis becomes a dependency on the claim path. A lock is **not** a correctness guarantee (lease expiry, failover), which is why ADR-0005 adds database-level protection.
- ➖ Lock wait time must be monitored (`shiftgrab.lock.wait` metric).

## 🇻🇳 Tóm tắt
Thay `ReentrantLock` (chỉ có tác dụng trong một JVM) bằng **khóa phân tán Redisson**, cài đặt sau interface `ShiftLockProvider` có sẵn (Strategy pattern). Khóa theo thứ tự cố định user → ca để tránh deadlock; chờ tối đa 3 giây, lease 10 giây. Chọn Redis thay vì `SELECT FOR UPDATE` vì không giữ kết nối DB khi chờ, và thay vì chỉ dùng optimistic lock vì tránh được bão retry giờ cao điểm. Lock không phải bảo đảm tuyệt đối, nên ADR-0005 bổ sung lớp bảo vệ ở database.
