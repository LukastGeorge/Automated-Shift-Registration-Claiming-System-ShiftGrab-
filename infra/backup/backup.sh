#!/bin/sh
# Daily database backups (SG-76, NFR-06: RPO 24 h). Runs in the "backup" container of the production stack:
# one backup at start-up, then every day at BACKUP_AT (organisation time). Keeps BACKUP_KEEP_DAYS days.
#
#   backup.sh           loop forever (container command)
#   backup.sh --once    one backup now (e.g. before a risky change)
set -eu

DIR=/backups
KEEP_DAYS="${BACKUP_KEEP_DAYS:-7}"
AT="${BACKUP_AT:-02:30}"
DATABASES="shiftgrab keycloak"

backup_once() {
    stamp=$(date -u +%Y%m%dT%H%M%SZ)
    for db in $DATABASES; do
        file="$DIR/$db-$stamp.dump"
        # Custom format: compressed, and pg_restore can restore all or part of it. Written to .part first so a
        # crash never leaves a truncated file that looks like a valid backup.
        pg_dump --host=postgres --username="$PGUSER" --dbname="$db" --format=custom --file="$file.part"
        mv "$file.part" "$file"
        echo "backup: $file ($(du -h "$file" | cut -f1))"
    done
    find "$DIR" -name '*.dump' -mtime +"$((KEEP_DAYS - 1))" -print -delete | sed 's/^/backup: removed old /'
    date -u +%s > "$DIR/.last-success"
}

seconds_until_next_run() {
    hour=${AT%:*}
    minute=${AT#*:}
    now=$(( $(date +%H | sed 's/^0//') * 3600 + $(date +%M | sed 's/^0//') * 60 + $(date +%S | sed 's/^0//') ))
    target=$(( ${hour#0} * 3600 + ${minute#0} * 60 ))
    wait=$(( (target - now + 86400) % 86400 ))
    [ "$wait" -eq 0 ] && wait=86400
    echo "$wait"
}

if [ "${1:-}" = "--once" ]; then
    backup_once
    exit 0
fi

backup_once
while :; do
    wait=$(seconds_until_next_run)
    echo "backup: next run in $((wait / 3600)) h $((wait % 3600 / 60)) min (at $AT)"
    sleep "$wait"
    backup_once || echo "backup: FAILED, will retry at the next run" >&2
done
