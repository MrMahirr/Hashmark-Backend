// Sahte GitHub (OAuth + API). Yük testinde gerçek GitHub'a istek atılmaz.
// Bağımlılık yok:  node mock-github.js   (PORT, LATENCY_MS ortam değişkenleri)
//
// code "u<N>-..." ise kullanıcı N'ye karşılık gelen bir GitHub hesabı döner (yeni kullanıcı oluşturur / günceller).
const http = require('http');

const PORT = Number(process.env.PORT || 9000);
const LATENCY_MS = Number(process.env.LATENCY_MS || 150); // gerçek GitHub gecikmesine yakın

function reply(res, status, body) {
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
