// Sahte GitHub (OAuth + API). Yük testinde gerçek GitHub'a istek atılmaz.
// Bağımlılık yok:  node mock-github.js   (PORT, LATENCY_MS ortam değişkenleri)
//
// code "u<N>-..." ise kullanıcı N'ye karşılık gelen bir GitHub hesabı döner (yeni kullanıcı oluşturur / günceller).
const http = require('http');

const PORT = Number(process.env.PORT || 9000);
const LATENCY_MS = Number(process.env.LATENCY_MS || 150); // gerçek GitHub gecikmesine yakın
// Kesinti taklidi (Faz 3 devre kesici ölçümü): "slow" = yanıt 30 sn gecikir (zaman aşımı), "error" = 502.
// Çalışırken değiştirmek için: curl -X POST mock:9000/__mode?m=slow|error|ok
let mode = process.env.MODE || 'ok';

function reply(res, status, body) {
  if (mode === 'slow') {
    setTimeout(() => {
      if (!res.writableEnded) { res.writeHead(504); res.end(); }
    }, 30000);
    return;
  }
  if (mode === 'error') status = 502;
  setTimeout(() => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(body));
  }, LATENCY_MS);
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  });
}

http
  .createServer(async (req, res) => {
    if (req.method === 'POST' && req.url.startsWith('/__mode')) {
      mode = new URL(req.url, 'http://x').searchParams.get('m') || 'ok';
      console.log('mode=' + mode);
      res.writeHead(204);
      return res.end();
    }
    if (req.method === 'POST' && req.url.startsWith('/login/oauth/access_token')) {
      const body = JSON.parse((await readBody(req)) || '{}');
      const code = String(body.code || 'u0-0');
      return reply(res, 200, { access_token: 'tok-' + code.split('-')[0], token_type: 'bearer', scope: 'repo' });
    }
    if (req.method === 'GET' && req.url.startsWith('/user/repos')) {
      return reply(res, 200, []);
    }
    if (req.method === 'GET' && req.url.startsWith('/user')) {
      const token = String(req.headers.authorization || '').replace('Bearer ', '');
      const n = token.replace('tok-u', '') || '0';
      return reply(res, 200, {
        id: String(900000 + Number(n)),
        login: 'loadtest-' + n,
        name: 'Load Test ' + n,
        email: 'loadtest-' + n + '@example.invalid',
        avatar_url: 'https://example.invalid/a.png',
      });
    }
    reply(res, 404, { message: 'Not Found' });
  })
  .listen(PORT, () => console.log('mock github listening on ' + PORT + ', latency ' + LATENCY_MS + 'ms'));
