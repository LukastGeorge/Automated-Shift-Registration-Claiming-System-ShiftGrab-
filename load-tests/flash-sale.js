// SG-80 flash sale: 1000 staff hit 10 shifts of 5 slots (50 slots) at the same instant, through the edge proxy and
// both backends.
//
// Pass criteria (correctness, stable on any machine):
//   - exactly SHIFTS x SLOTS claims succeed and every shift ends exactly full (no overbooking);
//   - every staff member ends with 201 or 409 SHIFT_FULL. Like a person reading "retry in N s" in the app, a VU
//     that gets 503 + Retry-After (CLAIM_BUSY, SERVICE_BUSY, LOCK_UNAVAILABLE) waits that long and claims again.
//     A 503 means the claim was rolled back, so the retry must never meet ALREADY_REGISTERED: if it does, the run
//     fails, because the server answered "busy" for a claim it had in fact saved.
// Measured and reported, not gating: claim latency (first attempt) and how many answers were 503. On one laptop
// they vary with whatever else the machine is doing (load-tests/results/03-reliability.md). Set P95_MS to gate
// the first-attempt p95 as well (NFR-02 is 300 ms at steady load, on a server).
//
//   docker run --rm --network shiftgrab_default -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run \
//     -e BASE=http://edge /scripts/flash-sale.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://edge';
const USERS = Number(__ENV.USERS || 1000);
const SHIFTS = Number(__ENV.SHIFTS || 10);
const SLOTS = Number(__ENV.SLOTS || 5);
const MAX_ATTEMPTS = Number(__ENV.MAX_ATTEMPTS || 5);
const TOKEN_URL = `${BASE}/auth/realms/shiftgrab/protocol/openid-connect/token`;

const claimed = new Counter('claims_succeeded');
const full = new Counter('claims_shift_full');
const other = new Counter('claims_other_outcome');
const busy = new Counter('claims_busy_answers'); // every 503 + Retry-After, tagged with its code
const retried = new Counter('claims_retried'); // staff who needed at least one retry
const timeToAnswer = new Trend('claim_time_to_final_answer', true); // including waits and retries

const thresholds = {
  claims_succeeded: [`count==${SHIFTS * SLOTS}`], // every slot taken, none twice
  claims_other_outcome: ['count==0'], // after retries: no 5xx, no unexpected code
  checks: ['rate==1'],
  // k6 only reports a tagged sub-metric that a threshold names, so this always-true one keeps the first-attempt
  // latency in the summary (and in --summary-export) without gating on it.
  'http_req_duration{name:claim}': __ENV.P95_MS ? [`p(95)<${Number(__ENV.P95_MS)}`] : ['max>=0'],
};

export const options = {
  setupTimeout: '10m',
  scenarios: {
    flash_sale: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '3m' },
  },
  thresholds,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function errorCode(res) {
  try {
    return res.json('code');
  } catch (e) {
    return undefined;
  }
}

function token(username, password) {
  const res = http.post(TOKEN_URL, { grant_type: 'password', client_id: 'shiftgrab-dev-cli', username, password },
    { tags: { name: 'token' } });
  if (res.status !== 200) throw new Error(`token for ${username}: HTTP ${res.status}`);
  return res.json('access_token');
}

export function setup() {
  const manager = token('manager', 'manager123');
  const auth = { headers: { Authorization: `Bearer ${manager}`, 'Content-Type': 'application/json' } };

  // Shifts a week ahead, open for registration already, one per day so nobody's shifts overlap.
  const shifts = [];
  for (let i = 0; i < SHIFTS; i++) {
    const start = new Date(Date.now() + (7 + i) * 86_400_000);
    start.setUTCHours(1, 0, 0, 0);
    const res = http.post(`${BASE}/api/v1/shifts`, JSON.stringify({
      name: `Flash sale ${i + 1}`, startAt: start.toISOString(),
      endAt: new Date(start.getTime() + 4 * 3_600_000).toISOString(),
      registrationOpenAt: new Date(Date.now() - 60_000).toISOString(), maxSlots: SLOTS,
    }), { ...auth, tags: { name: 'create-shift' } });
    if (res.status !== 201) throw new Error(`create shift: HTTP ${res.status} ${res.body}`);
    shifts.push(res.json('id'));
  }

  // Tokens for every staff member, fetched in batches before the sale starts.
  const tokens = [];
  for (let from = 0; from < USERS; from += 50) {
    const batch = [];
    for (let i = from; i < Math.min(from + 50, USERS); i++) {
      batch.push(['POST', TOKEN_URL, { grant_type: 'password', client_id: 'shiftgrab-dev-cli',
        username: `staff${String(i).padStart(4, '0')}`, password: 'load123' }, { tags: { name: 'token' } }]);
    }
    for (const res of http.batch(batch)) {
      if (res.status !== 200) throw new Error(`staff token: HTTP ${res.status}`);
      tokens.push(res.json('access_token'));
    }
  }

  // Everyone opens the app before the countdown ends, like real staff waiting on the page: the SPA's first
  // GET /me creates their user row. Without this, every claim would also pay a first-login INSERT that no real
  // flash sale has (found in run 02).
  for (let from = 0; from < tokens.length; from += 50) {
    const batch = tokens.slice(from, from + 50).map((t) => ['GET', `${BASE}/api/v1/me`, null,
      { headers: { Authorization: `Bearer ${t}` }, tags: { name: 'warm-up' } }]);
    for (const res of http.batch(batch)) {
      if (res.status !== 200) throw new Error(`GET /me: HTTP ${res.status}`);
    }
  }

  // Production servers are warm. Fresh containers are not: the first claims would also pay class loading and JIT
  // compilation (a 0.3 s "Metadata GC Threshold" pause in run 06). 200 claims on a separate shift 40 days out
  // (other day and ISO week, so no overlap or weekly-limit effect) exercise both the success and the full path.
  const warm = http.post(`${BASE}/api/v1/shifts`, JSON.stringify({
    name: 'Warm-up', startAt: new Date(Date.now() + 40 * 86_400_000).toISOString(),
    endAt: new Date(Date.now() + 40 * 86_400_000 + 3_600_000).toISOString(),
    registrationOpenAt: new Date(Date.now() - 60_000).toISOString(), maxSlots: SLOTS,
  }), { ...auth, tags: { name: 'create-shift' } }).json('id');
  for (let from = 0; from < Math.min(200, tokens.length); from += 50) {
    http.batch(tokens.slice(from, from + 50).map((t) => ['POST', `${BASE}/api/v1/shifts/${warm}/registrations`,
      null, { headers: { Authorization: `Bearer ${t}` }, tags: { name: 'warm-up' } }]));
  }
  return { shifts, tokens, manager };
}

export default function (data) {
  const vu = exec.vu.idInTest - 1;
  const shiftId = data.shifts[vu % data.shifts.length];
  const params = { headers: { Authorization: `Bearer ${data.tokens[vu]}` } };
  const started = Date.now();
  let res;
  for (let attempt = 1; ; attempt++) {
    // Only the first attempt is tagged "claim", so its latency is not mixed with deliberate waits.
    res = http.post(`${BASE}/api/v1/shifts/${shiftId}/registrations`, null,
      { ...params, tags: { name: attempt === 1 ? 'claim' : 'claim-retry' } });
    const retryAfter = Number(res.headers['Retry-After']);
    if (res.status !== 503 || !(retryAfter > 0)) break;
    busy.add(1, { code: errorCode(res) || 'none' });
    if (attempt === 1) retried.add(1);
    if (attempt === MAX_ATTEMPTS) break;
    sleep(retryAfter + Math.random() * 0.5); // people do not all press again at the same instant
  }
  timeToAnswer.add(Date.now() - started);

  if (res.status === 201) {
    claimed.add(1);
  } else if (res.status === 409 && errorCode(res) === 'SHIFT_FULL') {
    full.add(1);
  } else {
    other.add(1);
    console.warn(`unexpected final claim answer: ${res.status} ${res.body}`);
  }
  check(res, {
    'claim ended with 201 or 409 SHIFT_FULL': (r) => r.status === 201 || (r.status === 409 && errorCode(r) === 'SHIFT_FULL'),
  });
}

export function teardown(data) {
  // Zero overbooking, read back through the API: every shift exactly full, never above its slots.
  const auth = { headers: { Authorization: `Bearer ${data.manager}` } };
  for (const id of data.shifts) {
    const shift = http.get(`${BASE}/api/v1/shifts/${id}`, { ...auth, tags: { name: 'verify' } }).json();
    check(shift, {
      'shift exactly full (no overbooking)': (s) => s.bookedSlots === SLOTS && s.status === 'FULL',
    });
  }
}
