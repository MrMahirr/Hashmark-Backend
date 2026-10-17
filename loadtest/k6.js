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
import { Counter, Trend } from 'k6/metrics';

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

// Faz 3 senaryoları: birden fazla eşzamanlı k6 senaryosu
const OUTAGE = { start: 20, end: 80, total: 110 }; // sn: 0–20 normal, 20–80 GitHub yavaş, 80–110 toparlanma
const multi = {
  // (d) GitHub kesintisi: giriş + okuma birlikte; GitHub 20–80 sn arası yanıt vermez (30 sn gecikme)
  outage: {
    logins: { executor: 'constant-vus', vus: 100, duration: `${OUTAGE.total}s`, exec: 'outageLogin' },
    readers: { executor: 'constant-vus', vus: 100, duration: `${OUTAGE.total}s`, exec: 'outageRead' },
    chaos: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: `${OUTAGE.total + 10}s`, exec: 'chaos' },
  },
  // (e) Yük atma: dashboard yükü + sürekli export; küçük DB havuzuyla (EXTRA_ENV) havuz baskısı oluşur
  shed: {
    readers: {
      executor: 'ramping-vus', startVUs: 0, exec: 'dashboard',
      stages: [{ duration: '10s', target: VUS }, { duration: DURATION, target: VUS }, { duration: '5s', target: 0 }],
    },
    exporters: { executor: 'constant-vus', vus: 20, duration: DURATION, startTime: '10s', exec: 'exporter' },
  },
};

// Faz bazlı ölçüm (outage): "before" / "outage" / "after"
const loginByPhase = new Trend('callback_ms', true);
const readByPhase = new Trend('read_ms', true);
const callbackStatus = new Counter('callback_status');
const exportStatus = new Counter('export_status');

export function setup() {
  return { start: Date.now() };
}

function phase(data) {
  const s = (Date.now() - data.start) / 1000;
  return s < OUTAGE.start ? 'before' : s < OUTAGE.end ? 'outage' : 'after';
}

export const options = {
  scenarios: multi[SCENARIO] || { [SCENARIO]: scenarios[SCENARIO] },
  thresholds: {
    status_5xx_unexpected: ['count<1'],
    status_503: ['count<50'],
    http_req_failed: [{ threshold: 'rate<0.01', abortOnFail: false }],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// Etiketli alt metrikler k6 özetinde yalnızca bir eşiğe bağlıysa görünür: ölçüm amaçlı (her zaman geçen) eşikler
function observe(metric, tagSets, rule) {
  tagSets.forEach((tags) => { options.thresholds[`${metric}{${tags}}`] = [rule]; });
}
if (SCENARIO === 'outage') {
  const phases = ['phase:before', 'phase:outage', 'phase:after'];
  observe('callback_ms', phases, 'p(95)>=0');
  observe('read_ms', phases.map((p) => `${p},ok:true`), 'p(95)>=0');
  observe('read_ms', phases.map((p) => `${p},ok:false`), 'max>=0');
  ['200', '502', '503', '504', '0'].forEach((s) =>
    observe('callback_status', phases.map((p) => `${p},status:${s}`), 'count>=0'));
}
if (SCENARIO === 'shed') {
  observe('export_status', ['status:200', 'status:503', 'status:429'], 'count>=0');
}

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

const MOCK = __ENV.MOCK_URL || 'http://hmlt-mock:9000';

export function chaos() {
  sleep(OUTAGE.start);
  http.post(`${MOCK}/__mode?m=slow`);
  sleep(OUTAGE.end - OUTAGE.start);
  http.post(`${MOCK}/__mode?m=ok`);
}

export function outageLogin(data) {
  const p = phase(data);
  const a = http.get(`${BASE}/auth/github`, { tags: { name: 'GET /auth/github' } });
  track(a);
  if (a.status !== 200) { sleep(1); return; }
  const state = a.json('authUrl').split('state=')[1].split('&')[0];
  const code = `u${(__VU * 100000 + __ITER) % 1000000}-${__ITER}`;
  const b = http.get(`${BASE}/auth/callback?code=${code}&state=${state}`,
    { tags: { name: 'GET /auth/callback' }, timeout: '60s' });
  track(b);
  loginByPhase.add(b.timings.duration, { phase: p });
  callbackStatus.add(1, { phase: p, status: String(b.status) });
  sleep(1);
}

export function outageRead(data) {
  const p = phase(data);
  const userId = ((__VU - 1) % 200) + 1;
  const r = http.get(`${BASE}/report/summary`,
    { tags: { name: 'GET /report/summary' }, headers: { Authorization: `Bearer ${accessToken(userId)}` } });
  track(r);
  readByPhase.add(r.timings.duration, { phase: p, ok: String(r.status === 200) });
  sleep(0.5 + Math.random());
}

export function exporter() {
  const userId = ((__VU - 1) % 200) + 1;
  const r = http.get(`${BASE}/debts/export?format=csv`,
    { tags: { name: 'GET /debts/export' }, headers: { Authorization: `Bearer ${accessToken(userId)}` } });
  if (r.status >= 500 && r.status !== 503) status5xx.add(1);
  exportStatus.add(1, { status: String(r.status) });
  sleep(0.5);
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
