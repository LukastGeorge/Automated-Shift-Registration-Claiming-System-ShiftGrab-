import { expect, test } from '@playwright/test';

import { accounts, login, orgDate } from './support';

/**
 * SG-82 smoke: a manager publishes a shift, a staff member claims it and cancels it again, and the manager's list
 * follows both changes live (SG-40) without reloading. Two separate browser contexts = two people, two sessions.
 */
test('manager creates a shift, staff claims and cancels it, the manager sees it live', async ({ browser }) => {
  const name = `E2E ${Date.now()}`;
  // Three days ahead: the cancellation deadline is 24 h before the start (BR-06), so the shift stays cancellable.
  const day = orgDate(3);

  const managerContext = await browser.newContext();
  const staffContext = await browser.newContext();
  const manager = await managerContext.newPage();
  const staff = await staffContext.newPage();
  // SG-92: the whole journey must run under the Content-Security-Policy without a single violation
  const cspViolations: string[] = [];
  for (const page of [manager, staff]) {
    page.on('console', (msg) => {
      if (/Content Security Policy/i.test(msg.text())) cspViolations.push(msg.text());
    });
  }

  await test.step('manager logs in and creates a 2-slot shift that is open for registration now', async () => {
    await login(manager, accounts.manager, /\/manage\/shifts$/);
    await manager.getByTestId('new-shift').click();
    await manager.getByTestId('name').fill(name);
    await manager.getByTestId('start').fill(`${day}T09:00`);
    await manager.getByTestId('end').fill(`${day}T13:00`);
    // "opens at" keeps its default (now), so staff can claim at once
    await manager.getByTestId('maxSlots').fill('2');
    await manager.getByTestId('save').click();
  });

  const row = manager.locator('tr[data-testid^="row-"]', { hasText: name });
  await expect(row).toContainText('0/2');

  await test.step('staff logs in and claims the shift', async () => {
    await login(staff, accounts.staff, /\/shifts$/);
    const card = staff.locator('mat-card[data-testid^="shift-"]', { hasText: name });
    await card.locator('button[data-testid^="claim-"]').click();
    // The card now offers "my shift" instead of the claim button, and its meter shows one booked slot
    await expect(card.locator('a[href="/my-shifts"]')).toBeVisible();
    await expect(card.getByRole('meter')).toHaveAttribute('aria-valuenow', '1');
  });

  await test.step('the manager sees the claim live, without reloading', async () => {
    await expect(row).toContainText('1/2');
  });

  await test.step('staff cancels the registration from "my shifts"', async () => {
    await staff.goto('/my-shifts');
    const item = staff.locator('li[data-testid^="reg-"]', { hasText: name });
    await item.locator('button[data-testid^="cancel-"]').click();
    await staff.getByTestId('confirm').click();
    await expect(item.locator('button[data-testid^="cancel-"]')).toHaveCount(0);
  });

  await test.step('the manager sees the slot come back live', async () => {
    await expect(row).toContainText('0/2');
  });

  expect(cspViolations).toEqual([]);
  await managerContext.close();
  await staffContext.close();
});
