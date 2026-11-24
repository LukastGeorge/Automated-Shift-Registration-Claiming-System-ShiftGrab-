import { expect, Page } from '@playwright/test';

/** Demo accounts of the production realm; passwords come from the environment, as on the server. */
export const accounts = {
  manager: { username: 'manager', password: process.env.E2E_MANAGER_PASSWORD ?? 'manager123' },
  staff: { username: 'staff1', password: process.env.E2E_STAFF_PASSWORD ?? 'staff123' },
};

/**
 * Logs in through the real Keycloak page (authorization code + PKCE), exactly like a person: the app redirects to
 * Keycloak, we type the credentials, Keycloak redirects back and the app lands on the role's home page.
 */
export async function login(page: Page, account: { username: string; password: string }, home: RegExp): Promise<void> {
  await page.goto('/');
  await page.locator('#username').fill(account.username);
  await page.locator('#password').fill(account.password);
  await page.locator('#kc-login').click();
  await expect(page).toHaveURL(home);
}

/** "YYYY-MM-DD" a number of days from now in the organisation's time zone (the zone the shift form uses). */
export function orgDate(daysFromNow: number, timeZone = 'Asia/Ho_Chi_Minh'): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(
    new Date(Date.now() + daysFromNow * 86_400_000),
  );
}
