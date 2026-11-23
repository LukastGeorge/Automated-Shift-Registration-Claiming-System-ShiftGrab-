#!/bin/sh
# Restores a database from a backup made by infra/backup/backup.sh (SG-76, NFR-06: RTO 1 h).
# Run from the deployment directory:
#
#   infra/scripts/restore.sh                                  list the available backups
#   infra/scripts/restore.sh shiftgrab-20261123T023000Z.dump  restore that file into its database
#
# The application (or Keycloak) is stopped during the restore so nobody writes to a half-restored database,
# then started again. The current data is replaced: take a fresh backup first if in doubt (backup.sh --once).
set -eu

cd "$(dirname "$0")/../.."
ENV_FILE="${ENV_FILE:-infra/docker/.env.prod}"
compose() { docker compose -f infra/docker/docker-compose.prod.yml --env-file "$ENV_FILE" "$@"; }

if [ $# -eq 0 ]; then
    compose exec -T backup sh -c 'ls -lh /backups/*.dump'
    exit 0
fi

FILE=$(basename "$1")
DB=${FILE%%-*}
case "$DB" in
    shiftgrab) SERVICES="backend-1 backend-2" ;;
    keycloak) SERVICES="keycloak" ;;
    *) echo "Unknown database in file name: $FILE" >&2; exit 1 ;;
esac
compose exec -T backup test -f "/backups/$FILE" || { echo "No such backup: $FILE" >&2; exit 1; }

echo "Stopping $SERVICES"
compose stop $SERVICES
echo "Restoring $FILE into $DB"
# --clean --if-exists drops each object before recreating it; one transaction: all or nothing.
compose exec -T backup pg_restore --host=postgres --username=shiftgrab --dbname="$DB" \
    --clean --if-exists --no-owner --single-transaction "/backups/$FILE"
echo "Starting $SERVICES"
compose start $SERVICES
echo "Restored $DB from $FILE"
