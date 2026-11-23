#!/bin/sh
# Picks the site configuration at start-up: HTTPS when a Let's Encrypt certificate for $DOMAIN exists, plain HTTP
# otherwise (first boot, local test). A background loop repeats the choice and reloads nginx every 6 hours, so a
# renewed certificate, or the very first one, is picked up without a manual restart.
set -eu

select_site() {
    if [ -f "/etc/letsencrypt/live/${DOMAIN}/fullchain.pem" ]; then site=https; else site=http; fi
    # shellcheck disable=SC2016 # literal on purpose: tells envsubst to replace only ${DOMAIN}, not nginx's $host
    envsubst '${DOMAIN}' < "/etc/nginx/shiftgrab/${site}.conf.template" > /etc/nginx/conf.d/default.conf
    echo "edge: serving ${DOMAIN} over ${site}"
}

select_site
( while :; do sleep 6h; select_site && nginx -s reload; done ) &
