# ShiftGrab Web (Angular)

Single-page app for staff (claim and cancel shifts) and managers (manage shifts, templates and rates).

| | |
|---|---|
| Framework | Angular 22 (standalone components, signals, zoneless change detection) |
| UI | Angular Material 3, light and dark theme from the system setting |
| Login | Keycloak via `keycloak-js`: Authorization Code + PKCE, no secret in the browser |
| Languages | Vietnamese (default) and English, switchable at runtime |
| Tests | Vitest + Angular TestBed, ESLint (angular-eslint) |

## Run locally

Start the infrastructure and the backend first (see [docs/runbook/local-development.md](../docs/runbook/local-development.md)), then:

```bash
npm ci
npm start            # http://localhost:4200, log in as manager/manager123 or staff1/staff123
```

## Quality checks (the same as CI)

```bash
npm run lint
npm test -- --watch=false
npm run build
```

## Configuration

Environment-specific settings live in [`public/config.json`](public/config.json) and are read at startup, not compiled in.
One build therefore runs everywhere: a deployment only replaces `config.json`.

| Key | Meaning |
|---|---|
| `apiUrl` | Backend base URL; empty string = same origin (behind the reverse proxy) |
| `keycloak.url`, `keycloak.realm`, `keycloak.clientId` | OIDC provider and the public client `shiftgrab-frontend` |
| `timeZone` | Organisation time zone; every time is shown and typed in this zone |

## Structure

```
src/app/
  core/       auth (Keycloak, interceptor, role guard), api clients, i18n, formatting, notifications
  layout/     shell: toolbar, navigation filtered by role
  features/   one folder per screen, lazy-loaded
  shared/     small reusable pieces (load state, error block)
```
