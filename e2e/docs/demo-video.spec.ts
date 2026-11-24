import { APIRequestContext, Browser, expect, test } from '@playwright/test';

import { accounts, login, orgDate } from '../tests/support';

/**
 * SG-91: the README demo. Staff1 watches the open shifts; a colleague claims one (the counter moves live, no reload),
 * then staff1 claims another. Creates its own shifts five and six days ahead, so it can run after capture.spec.ts.
 * The video is saved next to the screenshots right away, because Playwright empties test-results on every run.
 */
async function bearer(browser: Browser, account: { username: string; password: string }): Promise<string> {
  const page = await (await browser.newContext()).newPage();
  const call = page.waitForRequest((r) => r.url().includes('/api/v1/') && !!r.headers()['authorization']);
  await login(page, account, /\/(manage\/)?shifts$/);
  const token = (await call).headers()['authorization'];
  await page.context().close();
  return token;
}

async function shift(api: APIRequestContext, token: string, name: string, day: number, from: string, to: string,
  slots: number): Promise<number> {
  const date = orgDate(day);
  const res = await api.post('/api/v1/shifts', {
    headers: { Authorization: token },
    data: { name, startAt: `${date}T${from}:00+07:00`, endAt: `${date}T${to}:00+07:00`,
      registrationOpenAt: new Date(Date.now() - 60_000).toISOString(), maxSlots: slots },
  });
  expect(res.status(), await res.text()).toBe(201);
  return (await res.json()).id;
}

test('demo video: live update and claim', async ({ browser, request }) => {
  const manager = await bearer(browser, accounts.manager);
  const colleague = await bearer(browser, { username: 'staff2', password: accounts.staff.password });
  const brunch = await shift(request, manager, 'Brunch service', 5, '09:00', '13:00', 4);
  await shift(request, manager, 'Closing shift', 5, '18:00', '23:00', 2);
  await shift(request, manager, 'Barista training', 6, '08:00', '11:00', 3);

  const context = await browser.newContext({
    viewport: { width: 1100, height: 680 },
    recordVideo: { dir: 'test-results/video', size: { width: 1100, height: 680 } },
  });
  await context.addInitScript(() => localStorage.setItem('shiftgrab.lang', 'en'));
  const page = await context.newPage();
  await login(page, accounts.staff, /\/shifts$/);
  await expect(page.getByTestId('live')).toHaveText(/^\s*Live\s*$/);
  const brunchCard = page.locator('mat-card[data-testid^="shift-"]', { hasText: 'Brunch service' });
  await brunchCard.scrollIntoViewIfNeeded();
  await page.waitForTimeout(1500);

  // A colleague claims on another device: this screen updates by itself
  const res = await request.post(`/api/v1/shifts/${brunch}/registrations`, { headers: { Authorization: colleague } });
  expect(res.status()).toBe(201);
  await expect(brunchCard).toContainText('3/4');
  await page.waitForTimeout(1800);

  // Then staff1 claims the closing shift
  const closing = page.locator('mat-card[data-testid^="shift-"]', { hasText: 'Closing shift' });
  await closing.locator('button[data-testid^="claim-"]').click();
  await expect(closing.locator('a[href="/my-shifts"]')).toBeVisible();
  await page.waitForTimeout(2500);

  const video = page.video();
  await context.close();
  await video?.saveAs('../docs/images/_demo.webm');
});
