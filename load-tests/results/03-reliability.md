# 03 · Reliability of the flash-sale test (after v1.0.0)

> 🇻🇳 Mục tiêu: test tải phải cho kết quả **chắc chắn**: đạt khi hệ thống đúng, trượt khi hệ thống sai, không
> phụ thuộc máy đang bận hay rảnh. Kết quả: 5/5 lần chạy liên tiếp đều đạt (50 suất, 0 overbooking, DB khớp HTTP).

## Why the test changed

Re-running the v1.0.0 flash sale on the same laptop gave a different verdict from run to run, while the system was
correct every time (exactly 50 claims, no overbooking, database consistent):

| Image | Run | `503 SERVICE_BUSY` | Pool waits over 1 s | Claims | Overbooking |
|---|---|---:|---:|---:|---:|
| v1.0.0 (Spring Boot 4.2.0) | 1 | 4 | 10 | 50 | 0 |
| v1.0.0 (Spring Boot 4.2.0) | 2 | 11 | 16 | 50 | 0 |
| before the upgrade (4.1.2) | 3 | 0 | 0 | 50 | 0 |
| v1.0.0 (Spring Boot 4.2.0) | 4 | 0 | 0 | 50 | 0 |
| before the upgrade (4.1.2) | 5 | 9 | 10 | 50 | 0 |

Alternating the two images under the same conditions shows **no regression**: both range from 0 to about 10
`503` answers depending on what else the laptop is doing. A `503` with `Retry-After` is the designed fail-fast
answer of SG-81 (the claim is rolled back and the app shows "retry in N s"), not an error, but the old test
counted it as a failure. A gate that flips with background load cannot tell a broken build from a busy machine.

## What changed

- **Retry like a person.** A VU that gets `503` + `Retry-After` waits that long (plus up to 0.5 s, people do not
  press again in the same millisecond) and claims again, up to 5 attempts.
- **The retry is a check of its own.** A `503` means nothing was saved, so a retry must never meet
  `ALREADY_REGISTERED`. If it did, the run would fail: the server would have answered "busy" for a claim it kept.
- **Gates are about correctness:** exactly `SHIFTS × SLOTS` claims, every staff member ends with `201` or
  `409 SHIFT_FULL`, every shift exactly full, and [`assert-db.sql`](../assert-db.sql) (no overbooking, counter =
  confirmed rows, no duplicate, claims in the database = `201` answers).
- **Reported, not gating:** first-attempt latency, time to the final answer, number of `503` answers. `P95_MS`
  re-enables a latency gate when measuring on a quiet server.
- **One command** ([`run-flash-sale.sh`](../run-flash-sale.sh)) for local runs and CI, on a fresh stack each time.
- **Start-up:** two runs had failed before k6 started, with `backend-1` reported unhealthy by `compose up --wait`.
  An idle start takes about 35 s, but the health check gave up after 60 s + 3 × 15 s while two backends and
  Keycloak started together on a busy machine. The start period is now 120 s (a healthy answer still counts at
  once, so a fast start is not delayed), and the runner prints container states and backend logs if start-up
  fails again, so the next occurrence leaves evidence.

## Result: 5 consecutive runs (1000 staff, 10 shifts × 5 slots)

Same laptop, fresh stack per run, 60 s settle, images built from this branch.

| Run | Host CPU before | Claims | `SHIFT_FULL` | Other final answers | `503` (retried) | Time to final answer p95 / max | Database check |
|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | 42 % | 50 | 950 | 0 | 0 | 1.53 s / 2.45 s | ✅ agrees |
| 2 | 26 % | 50 | 950 | 0 | 0 | 1.34 s / 2.98 s | ✅ agrees |
| 3 | 31 % | 50 | 950 | 0 | 1 | 1.53 s / 4.60 s | ✅ agrees |
| 4 | 31 % | 50 | 950 | 0 | 0 | 1.92 s / 3.89 s | ✅ agrees |
| 5 | 56 % | 50 | 950 | 0 | 0 | 1.83 s / 2.40 s | ✅ agrees |

**5 / 5 passed.** The single `503` (run 3) succeeded on its retry. These five runs exported only the time to the
final answer; the first-attempt latency sub-metric was missing from the summary file (k6 exports a tagged
sub-metric only when a threshold names it), fixed afterwards and checked with a CI-size run (200 staff, 10 slots:
10 claims, 190 `SHIFT_FULL`, database agrees, first-attempt p95 1.94 s).

Earlier the same day, three more runs with the old 60 s start period also passed (one with 31 `503` answers, all
successful on retry), which is the kind of variation the new gates no longer turn into a false failure.

## In CI

[`.github/workflows/load-test.yml`](../../.github/workflows/load-test.yml) runs the CI size (200 staff, 2 shifts ×
5 slots) nightly on `develop`, on every pull request into `main`, on pull requests into `develop` labelled
`load-test`, and on demand (the nightly schedule uses `main`'s copy of the workflow, so it starts with the next
release). It gates on the same correctness
checks and writes the counts and latency to the job summary. The full 1000-staff run stays a manual measurement.

## Also found while making the tests reliable

- `MultiInstanceClaimTests` (backend) failed once in a full local run: `{201=5, SHIFT_FULL=94, SERVICE_BUSY=1}`.
  It predated SG-81's pool and row-lock timeouts. Fixed in #140, then the whole backend suite passed 3 times in a
  row (186 / 186 each).
