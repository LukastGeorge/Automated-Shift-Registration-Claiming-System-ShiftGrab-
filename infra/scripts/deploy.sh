#!/bin/sh
# Deploys a version of ShiftGrab on the server (SG-75). Run from the deployment directory (e.g. /opt/shiftgrab):
#
#   infra/scripts/deploy.sh 0.7.0        deploy the images tagged 0.7.0
#   infra/scripts/deploy.sh --rollback   go back to the version deployed before the current one
#
# Rolling update: backend-1 is replaced and must become healthy before backend-2 is touched, so the edge proxy
# keeps serving the API from the other instance. Database migrations must therefore stay compatible with the
# previous version for one release (expand first, remove later).
set -eu

cd "$(dirname "$0")/../.."
ENV_FILE="${ENV_FILE:-infra/docker/.env.prod}"
compose() { docker compose -f infra/docker/docker-compose.prod.yml --env-file "$ENV_FILE" "$@"; }

if [ "${1:-}" = "--rollback" ]; then
    # Back to the last version known to work: the current one if the last deployment did not finish,
    # otherwise the one before it (the last deployment finished but failed its smoke test).
    if [ -s .deploying-version ]; then target=.deployed-version; else target=.previous-version; fi
    [ -s "$target" ] || { echo "No version recorded in $target, nothing to roll back to" >&2; exit 1; }
    VERSION=$(cat "$target")
    echo "Rolling back to $VERSION"
else
    VERSION="${1:?usage: deploy.sh <version> | --rollback}"
fi
CURRENT=$(cat .deployed-version 2>/dev/null || true)
# Overrides SHIFTGRAB_VERSION from the env file (shell variables win over --env-file in Compose).
export SHIFTGRAB_VERSION="$VERSION"

wait_healthy() {
    service=$1
    for _ in $(seq 1 60); do
        status=$(docker inspect -f '{{.State.Health.Status}}' "$(compose ps -q "$service")" 2>/dev/null || echo starting)
        if [ "$status" = healthy ]; then
            echo "  $service healthy"
            return 0
        fi
        sleep 5
    done
    echo "$service did not become healthy within 5 minutes" >&2
    compose logs --tail 50 "$service" >&2
    return 1
}

echo "$VERSION" > .deploying-version
if [ "${SKIP_PULL:-0}" = 1 ]; then
    echo "Using local images $VERSION (SKIP_PULL=1)"
else
    echo "Pulling images $VERSION"
    compose pull backend-1 backend-2 frontend
fi

echo "Infrastructure (no change if already up to date)"
compose up -d postgres redis keycloak mailpit prometheus grafana certbot backup

echo "Rolling update"
# One command per line on purpose: `set -e` ignores a failure on the left of `&&`, so
# `compose up ... && wait_healthy ...` would carry on after a failed start (found by a test with a missing image).
for service in backend-1 backend-2 frontend; do
    compose up -d --no-deps "$service"
    wait_healthy "$service"
done
compose up -d --no-deps edge

if [ -n "$CURRENT" ] && [ "$CURRENT" != "$VERSION" ]; then
    echo "$CURRENT" > .previous-version
fi
echo "$VERSION" > .deployed-version
rm -f .deploying-version
docker image prune -f > /dev/null
echo "Deployed $VERSION (previous: ${CURRENT:-none})"
