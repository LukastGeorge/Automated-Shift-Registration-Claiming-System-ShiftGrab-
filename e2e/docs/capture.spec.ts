import { APIRequestContext, Browser, expect, Page, test } from '@playwright/test';

import { accounts, login, orgDate } from '../tests/support';

/**
 * SG-91: seeds realistic data through the real API, then captures the README screenshots. Run it on a FRESH stack
 * (`docker compose ... down -v && up -d`): it is not idempotent on purpose. Then demo-video.spec.ts and grafana.spec.ts.
 */
const OUT = '../docs/images';

/** Logs a person in through Keycloak and returns the page plus the bearer token the app itself sends. */
async function session(browser: Browser, account: { username: string; password: string },
  home: RegExp): Promise<{ page: Page; token: string }> {
  const context = await browser.newContext();
  await context.addInitScript(() => localStorage.setItem('shiftgrab.lang', 'en'));
  const page = await context.newPage();
  const firstApiCall = page.waitForRequest((r) => r.url().includes('/api/v1/') && !!r.headers()['authorization']);
  await login(page, account, home);
  const token = (await firstApiCall).headers()['authorization'];
  return { page, token };
}

async function createShift(api: APIRequestContext, token: string, s: { name: string; day: number; from: string; to: string; slots: number; opensInMin?: number }): Promise<number> {
  const date = orgDate(s.day);
  const opens = new Date(Date.now() + (s.opensInMin ?? -60) * 60_000).toISOString();
  const res = await api.post('/api/v1/shifts', {
    headers: { Authorization: token },
    data: { name: s.name, startAt: `${date}T${s.from}:00+07:00`, endAt: `${date}T${s.to}:00+07:00`, registrationOpenAt: opens, maxSlots: s.slots },
  });
  expect(res.status(), await res.text()).toBe(201);
  return (await res.json()).id;
}

async function claim(api: APIRequestContext, token: string, shiftId: number): Promise<void> {
  const res = await api.post(`/api/v1/shifts/${shiftId}/registrations`, { headers: { Authorization: token } });
  expect(res.status(), await res.text()).toBe(201);
}

test('README screenshots', async ({ browser, request }) => {
  const manager = await session(browser, accounts.manager, /\/manage\/shifts$/);
  const staff2 = await session(browser, { username: 'staff2', password: accounts.staff.password }, /\/shifts$/);

  // A believable week for a cafÃ©/restaurant team
  const ids: Record<string, number> = {};
  for (const s of [
    { name: 'Morning bar', day: 1, from: '07:00', to: '11:00', slots: 3 },
    { name: 'Lunch service', day: 1, from: '11:00', to: '15:00', slots: 4 },
    { name: 'Evening service', day: 1, from: '17:00', to: '22:00', slots: 5 },
    { name: 'Bakery prep', day: 2, from: '06:00', to: '10:00', slots: 2 },
    { name: 'Weekend cashier', day: 2, from: '18:00', to: '23:00', slots: 3, opensInMin: 125 },
    { name: 'Inventory count', day: 3, from: '08:00', to: '12:00', slots: 2 },
    { name: 'Delivery desk', day: 4, from: '14:00', to: '20:00', slots: 3 },
  ]) {
    ids[s.name] = await createShift(request, manager.token, s);
  }
  await claim(request, staff2.token, ids['Bakery prep']);
  await claim(request, staff2.token, ids['Lunch service']);
  await claim(request, staff2.token, ids['Inventory count']);
  await claim(request, staff2.token, ids['Evening service']);
  const staff1 = await session(browser, accounts.staff, /\/shifts$/);

  await claim(request, staff1.token, ids['Morning bar']);
  await claim(request, staff1.token, ids['Bakery prep']); // the last slot: Bakery prep is now FULL
  await claim(request, staff1.token, ids['Delivery desk']);
  await staff1.page.context().close();

  // ---- Screenshots ----
  const staff = await session(browser, accounts.staff, /\/shifts$/);
  await staff.page.waitForTimeout(800);
  await staff.page.screenshot({ path: `${OUT}/staff-open-shifts.png` });
  await staff.page.goto('/my-shifts');
  await expect(staff.page.locator('li[data-testid^="reg-"]').first()).toBeVisible();
  await staff.page.screenshot({ path: `${OUT}/staff-my-shifts.png` });

  await manager.page.reload();
  await expect(manager.page.locator('tr[data-testid^="row-"]').first()).toBeVisible();
  await manager.page.screenshot({ path: `${OUT}/manager-shifts.png` });
  await manager.page.getByTestId('new-shift').click();
  await manager.page.getByTestId('name').fill('Holiday brunch');
  await manager.page.waitForTimeout(500);
  await manager.page.screenshot({ path: `${OUT}/manager-new-shift.png` });
  await manager.page.keyboard.press('Escape');
  await manager.page.goto('/manage/audit');
  await manager.page.waitForTimeout(1200);
  await manager.page.screenshot({ path: `${OUT}/manager-audit-log.png` });

  const phone = await browser.newContext({ ...{ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  await phone.addInitScript(() => localStorage.setItem('shiftgrab.lang', 'en'));
  const mobile = await phone.newPage();
  await login(mobile, accounts.staff, /\/shifts$/);
  await mobile.waitForTimeout(800);
  await mobile.screenshot({ path: `${OUT}/staff-mobile.png` });
});
