// Hashmark yük testi (k6). Senaryo seçimi: -e SCENARIO=login|dashboard|scan
//   BASE_URL   : backend adresi (varsayılan http://backend:8080)
//   JWT_SECRET : test backend'inin JWT_SECRET değeri (access token üretmek için)
//   DURATION   : sabit yük süresi (varsayılan 60s)
//   VUS        : eşzamanlı sanal kullanıcı (login: 500, dashboard: 200)
//
// Önkoşul: sahte GitHub çalışıyor, tohum verisi yüklü, backend şu ayarlarla başlatıldı:
//   GITHUB_OAUTH_URL / GITHUB_API_URL = sahte GitHub, RATE_LIMIT_AUTH_PER_MINUTE=1000000, RATE_LIMIT_SCAN_PER_MINUTE=1000000,
//   SCAN_MAX_CONCURRENT=0 (taramalar kuyrukta kalır; yalnızca "kuyruğa alma" yolu ölçülür).
// Üretim ortamında ÇALIŞTIRMAYIN.
import http from 'k6/http';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://backend:8080';
const SECRET = __ENV.JWT_SECRET;
const DURATION = __ENV.DURATION || '60s';
const SCENARIO = __ENV.SCENARIO || 'dashboard';
const VUS = Number(__ENV.VUS || (SCENARIO === 'login' ? 500 : 200));

const status5xx = new Counter('status_5xx_unexpected'); // 503 hariç 5xx = gerçek hata
const status429 = new Counter('status_429');
const status503 = new Counter('status_503');

const b64u = (s) => encoding.b64encode(s, 'rawurl');

function accessToken(userId) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64u(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = b64u(JSON.stringify({ sub: String(userId), typ: 'access', iat: now, exp: now + 900 }));
  const sig = crypto.hmac('sha256', SECRET, header + '.' + payload, 'base64rawurl');
  return header + '.' + payload + '.' + sig;
}

function track(res) {
  if (res.status >= 500 && res.status !== 503) status5xx.add(1);
  if (res.status === 429) status429.add(1);
  if (res.status === 503) status503.add(1);
}

const scenarios = {
  // (a) 500 eşzamanlı giriş patlaması: authUrl al -> state ile callback (sahte GitHub 2 çağrı).
  login: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '10s', target: VUS },
      { duration: DURATION, target: VUS },
      { duration: '5s', target: 0 },
    ],
    exec: 'login',
  },
  // (b) 200 kullanıcı dashboard gezinmesi
  dashboard: {
    executor: 'ramping-vus',
    startVUs: 0,
    stages: [
      { duration: '10s', target: VUS },
      { duration: DURATION, target: VUS },
      { duration: '5s', target: 0 },
    ],
    exec: 'dashboard',
  },
  // (c) 50 eşzamanlı kullanıcı POST /scan
  scan: {
    executor: 'per-vu-iterations',
    vus: 50,
    iterations: 1,
    maxDuration: '2m',
    exec: 'scan',
  },
};

export const options = {
  scenarios: { [SCENARIO]: scenarios[SCENARIO] },
  thresholds: {
    status_5xx_unexpected: ['count<1'],
    status_503: ['count<50'],
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: false }],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function login() {
  const a = http.get(`${BASE}/auth/github`, { tags: { name: 'GET /auth/github' } });
  track(a);
  if (!check(a, { 'authUrl 200': (r) => r.status === 200 })) return;
  const url = a.json('authUrl');
  const state = url.split('state=')[1].split('&')[0];
  const code = `u${(__VU * 100000 + __ITER) % 1000000}-${__ITER}`;
  const b = http.get(`${BASE}/auth/callback?code=${code}&state=${state}`, { tags: { name: 'GET /auth/callback' } });
  track(b);
  check(b, { 'callback 200': (r) => r.status === 200 });
  sleep(1);
}

export function dashboard() {
  const userId = ((__VU - 1) % 200) + 1;
  const params = { headers: { Authorization: `Bearer ${accessToken(userId)}` } };
  const get = (path, name) => {
    const r = http.get(`${BASE}${path}`, Object.assign({ tags: { name } }, params));
    track(r);
    check(r, { [`${name} 200`]: (x) => x.status === 200 });
    return r;
  };
  get('/repos', 'GET /repos');
  get('/debts/stats', 'GET /debts/stats');
  get('/report/summary', 'GET /report/summary');
  get('/debts?size=50&sort=detectedAt&dir=desc', 'GET /debts');
  get('/debts?size=50&status=OPEN&label=TODO', 'GET /debts?filter');
  sleep(1 + Math.random() * 2);
}

export function scan() {
  const userId = __VU; // 1..50
  const params = { headers: { Authorization: `Bearer ${accessToken(userId)}` } };
  for (let r = 1; r <= 3; r++) {
    const repoId = (userId - 1) * 3 + r;
    const res = http.post(`${BASE}/scan/${repoId}`, null, Object.assign({ tags: { name: 'POST /scan' } }, params));
    track(res);
    check(res, { 'scan accepted (200/202/409)': (x) => [200, 201, 202, 409].includes(x.status) });
  }
}
