# syntax=docker/dockerfile:1.7
# ShiftGrab web app (SG-70). Build:  docker build -t shiftgrab-frontend frontend
# One image for every environment: config.json is written from environment variables when the container starts.

# ---- build ---------------------------------------------------------------------------------------------------
FROM node:24-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---- runtime: nginx as an unprivileged user (port 8080) ------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.29-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist/shiftgrab-web/browser /usr/share/nginx/html
COPY --chmod=755 docker/40-write-config.sh /docker-entrypoint.d/40-write-config.sh
USER root
# Security fixes released after the base image was built (Trivy found a fixable CRITICAL in brotli-libs, SG-73).
# The entrypoint script rewrites config.json at start-up, so the non-root nginx user must own it.
RUN apk upgrade --no-cache && chown 101:101 /usr/share/nginx/html/config.json
USER 101
ENV API_URL="" \
    KEYCLOAK_URL="http://localhost:8180" \
    KEYCLOAK_REALM="shiftgrab" \
    KEYCLOAK_CLIENT_ID="shiftgrab-frontend" \
    TIME_ZONE="Asia/Ho_Chi_Minh"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
