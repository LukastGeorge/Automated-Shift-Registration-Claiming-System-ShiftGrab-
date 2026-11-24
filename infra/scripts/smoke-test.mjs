// API-level smoke test of the production stack through the edge proxy (one origin); used after each deployment.
// The browser-level journey is in e2e/ (Playwright, SG-82).
// Usage: node infra/scripts/smoke-test.mjs http://localhost:8088 manager123 staff123
import crypto from 'node:crypto';

const BASE = process.argv[2] ?? 'http://localhost:8088';
const [MANAGER_PW, STAFF_PW] = [process.argv[3] ?? 'manager123', process.argv[4] ?? 'staff123'];
const KC = `${BASE}/auth/realms/shiftgrab/protocol/openid-connect`;
const API = `${BASE}/api/v1`;
const REDIRECT = `${BASE}/`;
const b64url = (b) => b.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
let failures = 0;
const check = (ok, msg) => { console.log(`${ok ? '✓' : '✗'} ${msg}`); if (!ok) failures++; };

async function login(username, password) {
  const verifier = b64url(crypto.randomBytes(32));
  const challenge = b64url(crypto.createHash('sha256').update(verifier).digest());
  const page = await fetch(`${KC}/auth?` + new URLSearchParams({ client_id: 'shiftgrab-frontend', redirect_uri: REDIRECT,
    response_type: 'code', scope: 'openid', code_challenge: challenge, code_challenge_method: 'S256', state: 's' }), { redirect: 'manual' });
  if (page.status !== 200) throw new Error(`login page HTTP ${page.status}`);
  const cookies = page.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
  const action = (await page.text()).match(/action="([^"]+)"/)[1].replace(/&amp;/g, '&');
  const post = await fetch(action, { method: 'POST', redirect: 'manual', headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookies },
    body: new URLSearchParams({ username, password }) });
  const location = post.headers.get('location') ?? '';
  if (!location.startsWith(REDIRECT)) throw new Error(`login failed: ${post.status} ${location}`);
  const token = await (await fetch(`${KC}/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'authorization_code', client_id: 'shiftgrab-frontend', code: new URL(location).searchParams.get('code'),
      redirect_uri: REDIRECT, code_verifier: verifier }) })).json();
  return token.access_token;
}
const api = async (token, method, path, body, headers = {}) => {
  const res = await fetch(`${API}${path}`, { method, headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined });
  const text = await res.text();
  return { status: res.status, body: text && text.startsWith('{') ? JSON.parse(text) : text };
};

// On a first start Keycloak reports ready before the realm import finishes (seen: ~25 s), so a smoke test right
// after a deployment waits for the realm instead of failing.
async function waitForRealm(timeoutMs = 180_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if ((await fetch(`${BASE}/auth/realms/shiftgrab/.well-known/openid-configuration`)).ok) return;
    } catch { /* not reachable yet */ }
    await new Promise((r) => setTimeout(r, 3000));
  }
  throw new Error('realm not available');
}
await waitForRealm();

const home = await fetch(`${BASE}/manage/shifts`);
check(home.status === 200 && (await home.text()).includes('<app-root'), 'web app served by the edge (deep link)');
const config = await (await fetch(`${BASE}/config.json`)).json();
check(config.apiUrl === '' && config.keycloak.url === `${BASE}/auth`, `config.json from env: keycloak.url=${config.keycloak.url}`);
check((await fetch(`${BASE}/actuator/health`)).status === 404, 'actuator is not public');
check((await fetch(`${BASE}/auth/admin/`)).status === 404, 'Keycloak admin console is not public');
const devCli = await fetch(`${KC}/token`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({ grant_type: 'password', client_id: 'shiftgrab-dev-cli', username: 'staff1', password: STAFF_PW }) });
check(devCli.status >= 400, `no password-grant dev client in production (HTTP ${devCli.status})`);

const manager = await login('manager', MANAGER_PW);
const staff = await login('staff1', STAFF_PW);
check(!!manager && !!staff, 'PKCE login through /auth for manager and staff');
const iss = JSON.parse(Buffer.from(staff.split('.')[1], 'base64url').toString()).iss;
check(iss === `${BASE}/auth/realms/shiftgrab`, `token issuer is the public URL: ${iss}`);

const me = await api(staff, 'GET', '/me');
check(me.status === 200 && me.body.role === 'STAFF', `GET /me through the edge → ${me.status} ${me.body.fullName}`);

const start = new Date(Date.now() + 3 * 86_400_000); start.setUTCMinutes(0, 0, 0);
const shift = await api(manager, 'POST', '/shifts', { name: 'Edge smoke', startAt: start.toISOString(),
  endAt: new Date(start.getTime() + 4 * 3_600_000).toISOString(), registrationOpenAt: new Date(Date.now() - 60_000).toISOString(), maxSlots: 2 });
check(shift.status === 201, `manager creates a shift → ${shift.status}`);

// Live update over the proxied WebSocket while claiming through the load balancer
const ws = new WebSocket(`${BASE.replace(/^http/, 'ws')}/ws`);
const frames = [];
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
ws.onmessage = (e) => frames.push(String(e.data));
// Wait for a condition instead of a fixed delay: right after a deployment the JVMs are cold and slower, and a
// smoke test that decides on a rollback must not be flaky.
async function waitFor(condition, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = condition();
    if (value) return value;
    await new Promise((r) => setTimeout(r, 100));
  }
  return undefined;
}

ws.send(`CONNECT\naccept-version:1.2\nhost:localhost\nAuthorization:Bearer ${staff}\n\n\0`);
check(!!(await waitFor(() => frames.some((f) => f.startsWith('CONNECTED')))), 'STOMP CONNECT through the edge proxy');
ws.send('SUBSCRIBE\nid:sub-0\ndestination:/topic/shifts\nreceipt:subscribed\n\n\0');
await waitFor(() => frames.some((f) => f.startsWith('RECEIPT')), 5_000);

const claim = await api(staff, 'POST', `/shifts/${shift.body.id}/registrations`);
check(claim.status === 201, `staff claims through the load balancer → ${claim.status}`);
const live = await waitFor(() =>
  frames.find((f) => f.startsWith('MESSAGE') && f.includes(`"shiftId":${shift.body.id}`) && f.includes('"bookedSlots":1')));
check(!!live, 'live slot update received over WebSocket');
ws.close();

await api(manager, 'POST', `/shifts/${shift.body.id}/cancel`, { reason: 'edge smoke test cleanup' });
console.log(failures === 0 ? '\nALL CHECKS PASSED' : `\n${failures} CHECK(S) FAILED`);
process.exitCode = failures ? 1 : 0;
