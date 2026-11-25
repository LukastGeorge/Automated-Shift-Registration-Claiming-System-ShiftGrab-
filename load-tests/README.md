# Load tests (SG-80)

> 🇻🇳 Kịch bản "flash sale": 1000 nhân viên cùng giành 50 suất (10 ca × 5 suất) qua nginx và 2 backend.
> **Đạt khi:** đúng 50 người có ca, 0 overbooking, mọi người cuối cùng nhận 201 hoặc 409 `SHIFT_FULL` (nhận 503
> thì chờ `Retry-After` rồi bấm lại, như người dùng thật), và database khớp với các câu trả lời HTTP.
> Độ trễ và số lần 503 được đo và báo cáo nhưng không quyết định đạt/trượt, vì trên laptop chúng dao động theo
> tải của máy. CI chạy bản thu nhỏ (200 người, 10 suất) mỗi đêm và ở mọi PR vào `main`.

## Pass criteria

| Gate (fails the run) | How |
|---|---|
| Exactly `SHIFTS × SLOTS` claims succeed, every shift exactly full | k6 thresholds + teardown check through the API |
| Every staff member ends with `201` or `409 SHIFT_FULL` | A `503` with `Retry-After` (`CLAIM_BUSY`, `SERVICE_BUSY`, `LOCK_UNAVAILABLE`) is retried after that delay, up to 5 attempts, like a person pressing again |
| A retry never meets `ALREADY_REGISTERED` | A `503` means the claim was rolled back; finding it saved would be a bug |
| The database agrees with the HTTP answers | [`assert-db.sql`](assert-db.sql): no overbooking, counter = confirmed rows, no duplicate claim, claims in the DB = `201` answers |

Reported, not gating: first-attempt claim latency (`http_req_duration{name:claim}`), time to the final answer
including retries, and how many answers were `503` (`claims_busy_answers`, tagged with the code). Set `P95_MS` to
gate the first-attempt p95 too (NFR-02 is 300 ms at steady load, measured on a server).

## Run

One command does everything on a fresh, throwaway stack and removes it afterwards (`KEEP_STACK=1` keeps it):

```bash
load-tests/run-flash-sale.sh infra/docker/.env.local.example          # 1000 staff, 10 shifts x 5 slots
load-tests/run-flash-sale.sh infra/docker/.env.local.example 200 2 5  # the CI size
```

It generates the realm, starts the production stack with it on new volumes, waits 60 s for first-start work to
settle, runs k6 inside the compose network through the edge proxy, then runs `assert-db.sql`. Exit code: k6's
(`99` = a gate failed) or `3` if the database check fails. Images come from the env file (`IMAGE_PREFIX`,
`SHIFTGRAB_VERSION`), so build them from the commit you want to measure first.

<details><summary>The same steps by hand</summary>

```bash
# 1. Generate the throwaway realm: 1000 staff (staff0000..staff0999 / load123) + manager / manager123
node load-tests/prepare-realm.mjs

# 2. Start the production stack with that realm (fresh volumes, so Keycloak imports it)
docker compose -f infra/docker/docker-compose.prod.yml -f load-tests/docker-compose.loadtest.yml \
  --env-file <env file> up -d --wait

# 3. Run k6 inside the compose network, through the edge proxy
docker run --rm --network shiftgrab_default -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run \
  -e BASE=http://edge /scripts/flash-sale.js

# 4. Check the database agrees with the HTTP answers (verify-db.sql prints a table for a person)
docker compose -f infra/docker/docker-compose.prod.yml exec -T postgres \
  psql -U shiftgrab -d shiftgrab < load-tests/verify-db.sql
```

</details>

`USERS`, `SHIFTS` and `SLOTS` can be overridden with `-e`. The realm uses one password-hash iteration so
1000 logins take seconds — **never** import it anywhere but a disposable test stack.

## Results

| Run | p95 claim | CLAIM_BUSY | Correct (50 / 0 overbooking) |
|---|---:|---:|---|
| [01 baseline](results/01-baseline.md) | 6 120 ms | 138 | ✅ |
| [09 after SG-81 tuning](results/02-tuning.md) (runs 02–10 in the same report) | 3 557 ms on this laptop; server-side max 1 260 ms | **0** | ✅ |
| [Reliability: 5 runs in a row](results/03-reliability.md), retrying `503` like a person | time to final answer p95 1.3–1.9 s | 1 in 5 000 claims, succeeded on retry | ✅ 5 / 5 |

The script warms the backends up (200 claims on a separate shift) and opens the app for every user (`GET /me`)
before the sale, because production servers are warm and staff wait on the page. Measure on a fresh stack after
a short settle period, and preferably with k6 on another machine: on one laptop, k6 competes with the stack for
CPU and the client-side tail varies ±50 % between runs.
