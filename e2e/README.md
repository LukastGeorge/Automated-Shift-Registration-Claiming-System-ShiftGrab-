# End-to-end tests (SG-82)

> 🇻🇳 Playwright điều khiển trình duyệt thật trên **stack production thật**: nginx → Angular → đăng nhập Keycloak
> (PKCE) → 2 backend → PostgreSQL. Kịch bản: quản lý tạo ca, nhân viên giành rồi hủy, và màn hình quản lý tự cập
> nhật số suất trực tiếp. Chạy trên CI ở mọi PR vào `main`, mỗi đêm trên `develop`, và PR có nhãn `e2e`.

## What it covers

| Step | Checks |
|---|---|
| Manager logs in through the Keycloak page and creates a 2-slot shift | role-based landing page, shift form, list shows `0/2` |
| Staff logs in and claims it | claim button → "my shift", slot meter shows 1 booked |
| Manager's list, without reloading | live update over WebSocket: `1/2` |
| Staff cancels it from "my shifts" | confirmation dialog, cancel button disappears |
| Manager's list, without reloading | slot released live: `0/2` |

Selectors use `data-testid` attributes, so changing the language or a button's wording does not break the test.

## Run locally

```bash
# 1. A running production stack (see docs/runbook/production.md, "Test the production stack on a laptop")
# 2. Then:
cd e2e
npm ci
npx playwright install chromium
E2E_BASE_URL=http://localhost:8088 E2E_MANAGER_PASSWORD=... E2E_STAFF_PASSWORD=... npx playwright test
npx playwright show-report        # HTML report; failed runs keep a trace, screenshots and a video
```

Passwords default to the demo values `manager123` / `staff123` when the variables are not set.

## In CI

[`.github/workflows/e2e.yml`](../.github/workflows/e2e.yml) builds both images from the commit, starts the
production compose file with throwaway secrets, runs the tests and uploads the report, traces and container logs if
anything fails. It runs on:

- every pull request into `main` (required before a release);
- pull requests labelled `e2e`;
- nightly on `develop`;
- on demand.

## Found by this test

The first run caught a real bug that the unit tests missed. When the live update of a claim reached the browser
**before** the HTTP response, the open-shifts page added `+1` on top of the server's absolute count, so it showed
2 booked for 1 claim. Fixed in the same story: while live updates flow, the server's numbers are the only truth.
A regression unit test reproduces that ordering.
