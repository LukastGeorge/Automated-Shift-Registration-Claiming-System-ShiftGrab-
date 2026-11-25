#!/usr/bin/env bash
# One complete flash-sale run on a fresh, throwaway stack: generate the load-test realm, start the production
# stack with it (new volumes), let it settle, run k6 through the edge proxy, then check that the database agrees
# with the HTTP answers (assert-db.sql). Used by CI (load-test.yml) and for local runs; works in Git Bash too.
#
#   load-tests/run-flash-sale.sh <env file> [users] [shifts] [slots]
#   load-tests/run-flash-sale.sh infra/docker/.env.local.example            # 1000 staff, 10 x 5 slots
#   load-tests/run-flash-sale.sh "$RUNNER_TEMP/stack.env" 200 2 5           # the CI size
#
# The stack is removed afterwards (with its volumes) unless KEEP_STACK=1: the load-test realm uses one password
# hash iteration and must never outlive the run. Exit code: k6's (99 = a threshold failed), or 3 if the database
# check fails.
set -euo pipefail
cd "$(dirname "$0")/.."

ENV_FILE=${1:?usage: run-flash-sale.sh <env file> [users] [shifts] [slots]}
USERS=${2:-1000}
SHIFTS=${3:-10}
SLOTS=${4:-5}
SETTLE_SECONDS=${SETTLE_SECONDS:-60}
SUMMARY=load-tests/.generated/summary.json

compose() {
    docker compose -f infra/docker/docker-compose.prod.yml -f load-tests/docker-compose.loadtest.yml \
        --env-file "$ENV_FILE" "$@"
}
# shellcheck disable=SC2329 # called through the EXIT trap below
cleanup() {
    if [ "${KEEP_STACK:-0}" != 1 ]; then
        compose down -v --remove-orphans > /dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

node load-tests/prepare-realm.mjs "$USERS"
compose down -v --remove-orphans > /dev/null 2>&1 || true
if ! compose up -d --wait --wait-timeout 300; then
    # Keep the evidence: a start-up failure is otherwise lost when the stack is removed.
    echo "stack did not become healthy; container states and backend logs follow" >&2
    compose ps -a >&2 || true
    compose logs --no-color --tail 80 backend-1 backend-2 >&2 || true
    exit 1
fi

# Keycloak reports healthy before its realm import finishes: wait for the realm itself.
realm_ready=0
for _ in $(seq 1 60); do
    if curl -fsS -o /dev/null http://localhost:8088/auth/realms/shiftgrab/.well-known/openid-configuration; then
        realm_ready=1
        break
    fi
    sleep 3
done
[ "$realm_ready" = 1 ] || { echo "realm not ready" >&2; exit 1; }

# First-start work (Keycloak, Grafana) must not overlap the measurement.
echo "settling for ${SETTLE_SECONDS}s"
sleep "$SETTLE_SECONDS"

rm -f "$SUMMARY"
root=$(pwd -W 2> /dev/null || pwd) # Windows path for Docker Desktop when run from Git Bash
k6_env=(-e BASE=http://edge -e "USERS=$USERS" -e "SHIFTS=$SHIFTS" -e "SLOTS=$SLOTS")
if [ -n "${P95_MS:-}" ]; then
    k6_env+=(-e "P95_MS=$P95_MS")
fi
set +e
MSYS_NO_PATHCONV=1 docker run --rm --network shiftgrab_default -v "$root/load-tests:/scripts" grafana/k6:2.3.0 run \
    --quiet --summary-export /scripts/.generated/summary.json "${k6_env[@]}" /scripts/flash-sale.js
k6_status=$?
set -e

expected=$(node -e "const m = require('./$SUMMARY').metrics; console.log(m.claims_succeeded ? m.claims_succeeded.count : 0)")
compose exec -T postgres psql -q -v ON_ERROR_STOP=1 -v expected="$expected" -U shiftgrab -d shiftgrab \
    < load-tests/assert-db.sql
echo "database agrees with the HTTP answers ($expected claims)"
exit "$k6_status"
