#!/bin/sh
# Writes the runtime configuration read by the web app at start-up (see src/app/core/config.ts).
# Values come from the container environment, so the same image runs locally, in staging and in production.
set -eu

json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

cat > /usr/share/nginx/html/config.json <<EOF
{
  "apiUrl": "$(json_escape "${API_URL}")",
  "keycloak": {
    "url": "$(json_escape "${KEYCLOAK_URL}")",
    "realm": "$(json_escape "${KEYCLOAK_REALM}")",
    "clientId": "$(json_escape "${KEYCLOAK_CLIENT_ID}")"
  },
  "timeZone": "$(json_escape "${TIME_ZONE}")"
}
EOF
echo "config.json written (apiUrl='${API_URL}', keycloak='${KEYCLOAK_URL}')"
