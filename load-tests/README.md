# Load tests (SG-80)

> 🇻🇳 Kịch bản "flash sale": 1000 nhân viên cùng giành 50 suất (10 ca × 5 suất) qua nginx và 2 backend.
> Đạt khi: đúng 50 người có ca, 0 overbooking, p95 thời gian giành ca < 300 ms (NFR-02).

## Run

```bash
# 1. Generate the throwaway realm: 1000 staff (staff0000..staff0999 / load123) + manager / manager123
node load-tests/prepare-realm.mjs

# 2. Start the production stack with that realm (fresh volumes, so Keycloak imports it)
docker compose -f infra/docker/docker-compose.prod.yml -f load-tests/docker-compose.loadtest.yml \
  --env-file <env file> up -d --wait

# 3. Run k6 inside the compose network, through the edge proxy
docker run --rm --network shiftgrab_default -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run \
  -e BASE=http://edge /scripts/flash-sale.js

# 4. Check the database agrees with the HTTP answers
docker compose -f infra/docker/docker-compose.prod.yml exec -T postgres \
  psql -U shiftgrab -d shiftgrab < load-tests/verify-db.sql
```

`USERS`, `SHIFTS` and `SLOTS` can be overridden with `-e`. The realm uses one password-hash iteration so
1000 logins take seconds — **never** import it anywhere but a disposable test stack.

## Results

| Run | p95 claim | CLAIM_BUSY | Correct (50 / 0 overbooking) |
|---|---:|---:|---|
| [01 baseline](results/01-baseline.md) | 6 120 ms | 138 | ✅ |
