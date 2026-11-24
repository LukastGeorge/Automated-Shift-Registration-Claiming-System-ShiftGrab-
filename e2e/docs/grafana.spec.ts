import { expect, test } from '@playwright/test';

/**
 * SG-91: the monitoring screenshot. Run after capture.spec.ts (or any traffic), so the panels have data.
 * E2E_GRAFANA_PASSWORD is the stack's GRAFANA_ADMIN_PASSWORD.
 */
test('Grafana dashboard screenshot', async ({ page }) => {
  await page.goto('/grafana/login');
  await page.locator('input[name="user"]').fill('admin');
  await page.locator('input[name="password"]').fill(process.env.E2E_GRAFANA_PASSWORD ?? '');
  await page.locator('button[type="submit"]').click();
  await expect(page).not.toHaveURL(/\/login/); // wait until the session exists
  await page.goto('/grafana/d/shiftgrab-overview?orgId=1&from=now-10m&to=now&kiosk');
  // kiosk mode hides the title bar; wait for the first panel instead
  await expect(page.getByRole('heading', { name: 'Backend instances up' })).toBeVisible();
  await page.waitForTimeout(6000); // panels query Prometheus after the page renders
  await page.screenshot({ path: '../docs/images/grafana-overview.png' });
});
