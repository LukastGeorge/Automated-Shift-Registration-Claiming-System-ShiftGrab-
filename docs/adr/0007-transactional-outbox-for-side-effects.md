# ADR-0007: Side effects after commit through a transactional outbox (Spring Modulith event publication registry)

- **Status:** Accepted
- **Date:** 2026-11-22

## Context
SG-41 requires e-mails after a claim, a cancellation and a manager cancelling a shift, and "sending failures never roll back the claim". Two things can go wrong between the business transaction and the e-mail:
- the mail server is down or slow when the e-mail is sent;
- the backend instance dies after the commit but before the e-mail is sent.

Sending inside the transaction would make a claim fail (or wait) because of SMTP. Sending "after commit, fire and forget" loses the e-mail in both cases above. Sprint 5 (SG-40) adds live updates and Sprint 6 reports, which have the same problem.

The audit log (SG-52) is deliberately **not** handled this way: it is written in the business transaction, because a change without its audit entry is not acceptable.

## Options considered
1. **Send inside the transaction.** Simple, never lost, but SMTP latency and failures roll back or slow down claims. Rejected by the AC.
2. **`@Async` after commit, log failures.** Simple and fast, but e-mails are lost on a mail outage or crash, with no record of what was lost.
3. **Hand-written outbox table + poller.** Reliable, but a lot of custom code (serialisation, retries, cleanup, multi-instance coordination).
4. **Spring Modulith event publication registry.** The same outbox pattern, provided by the framework we already use for module boundaries: events for after-commit listeners are stored in `event_publication` in the business transaction and marked completed when the listener succeeds.

## Decision
Option 4.
- Listeners use `@ApplicationModuleListener` (after commit, asynchronous, own transaction).
- `event_publication` is created by Flyway (V5), schema v2 of `spring-modulith-events-jdbc` 2.1.
- A listener that throws leaves its publication `FAILED`. A publication still `PUBLISHED`/`PROCESSING` after 5 minutes belongs to a dead instance and is marked failed by Modulith's staleness monitor.
- `EventPublicationMaintenance` resubmits failed publications every minute and deletes completed ones after 7 days. Both jobs run under **ShedLock**, so with two instances a publication is never resubmitted twice in parallel.

## Consequences
- ➕ A mail outage never affects claims, and no e-mail is lost: it is sent when the server is back (verified by `NotificationOutboxTests`).
- ➕ Every pending side effect is visible in one table, which helps operations.
- ➖ **At-least-once delivery:** a listener that sent some e-mails and then failed is resubmitted as a whole, so a staff member may receive an e-mail twice. Accepted: a duplicate is better than a missing cancellation notice.
- ➖ Event classes are serialised as JSON and become a stored contract: renaming or changing an event must consider rows still pending.
- ➖ One more table and two scheduled jobs to operate.

## 🇻🇳 Tóm tắt
Email và các tác vụ phụ khác chạy **sau khi commit** qua **transactional outbox** có sẵn của Spring Modulith. Event được lưu vào bảng `event_publication` trong cùng transaction nghiệp vụ. Nếu gửi lỗi (mail server sập) hoặc instance chết giữa chừng, publication bị đánh dấu `FAILED`, rồi một job có ShedLock sẽ gửi lại mỗi phút. Kết quả: giành ca không bao giờ bị rollback vì lỗi email, và email cũng không bị mất. Cái giá phải trả là cơ chế "ít nhất một lần" (at-least-once): đôi khi một người có thể nhận trùng email. Audit log thì cố ý ghi trong cùng transaction, vì không được phép có thay đổi nào thiếu dòng audit.
