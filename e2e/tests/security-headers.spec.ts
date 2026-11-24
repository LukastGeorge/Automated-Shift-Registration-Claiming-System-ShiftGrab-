import { expect, test } from '@playwright/test';

/**
 * SG-92: every kind of response of the web app carries the security headers. Before SG-92, index.html lacked
 * Permissions-Policy because nginx drops inherited add_header directives in a location that declares its own.
 */
const paths = {
  'app route (index.html)': '/manage/shifts',
  'runtime config': '/config.json',
  'hashed asset': '/favicon.ico',
};

for (const [kind, path] of Object.entries(paths)) {
  test(`security headers on ${kind}`, async ({ request }) => {
    const res = await request.get(path);
    expect(res.status()).toBe(200);
    const h = res.headers();
    expect(h['content-security-policy']).toContain("script-src 'self'");
    expect(h['content-security-policy']).toContain("frame-ancestors 'none'");
    expect(h['permissions-policy']).toContain('camera=()');
    expect(h['x-content-type-options']).toBe('nosniff');
    expect(h['x-frame-options']).toBe('DENY');
    expect(h['referrer-policy']).toBe('strict-origin-when-cross-origin');
  });
}
