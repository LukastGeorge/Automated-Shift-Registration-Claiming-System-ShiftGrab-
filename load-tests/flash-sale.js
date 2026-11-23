// SG-80 flash sale: 1000 staff hit 10 shifts of 5 slots (50 slots) at the same instant, through the edge proxy and
// both backends. Pass criteria: exactly 50 claims succeed, nobody is overbooked, claim p95 < 300 ms (NFR-02).
//
//   docker run --rm --network shiftgrab_default -v "$PWD/load-tests:/scripts" grafana/k6:2.3.0 run \
//     -e BASE=http://edge /scripts/flash-sale.js
import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://edge';
const USERS = Number(__ENV.USERS || 1000);
const SHIFTS = Number(__ENV.SHIFTS || 10);
const SLOTS = Number(__ENV.SLOTS || 5);
const TOKEN_URL = `${BASE}/auth/realms/shiftgrab/protocol/openid-connect/token`;

const claimed = new Counter('claims_succeeded');
const full = new Counter('claims_shift_full');
const other = new Counter('claims_other_outcome');

export const options = {
  setupTimeout: '10m',
  scenarios: {
    flash_sale: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    'http_req_duration{name:claim}': ['p(95)<300'], // NFR-02
    claims_succeeded: [`count==${SHIFTS * SLOTS}`], // every slot taken, none twice
    claims_other_outcome: ['count==0'], // no 5xx, no CLAIM_BUSY, no unexpected code
    checks: ['rate==1'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

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
  return { shifts, tokens, manager };
}

export default function (data) {
  const vu = exec.vu.idInTest - 1;
  const shiftId = data.shifts[vu % data.shifts.length];
  const res = http.post(`${BASE}/api/v1/shifts/${shiftId}/registrations`, null,
    { headers: { Authorization: `Bearer ${data.tokens[vu]}` }, tags: { name: 'claim' } });

  if (res.status === 201) {
    claimed.add(1);
  } else if (res.status === 409 && res.json('code') === 'SHIFT_FULL') {
    full.add(1);
  } else {
    other.add(1);
    console.warn(`unexpected claim answer: ${res.status} ${res.body}`);
  }
  check(res, { 'claim answered 201 or 409 SHIFT_FULL': (r) => r.status === 201 || r.status === 409 });
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
