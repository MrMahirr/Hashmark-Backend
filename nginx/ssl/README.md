# SSL Certificates Directory

Bu dizin Nginx'in SSL sertifikalarını içerir.

## İlk Kurulum (Self-signed)

```bash
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout privkey.pem \
  -out fullchain.pem \
  -subj "/CN=hashmark.yunusemremahir.com.tr"
```

## Production (Let's Encrypt)

Detaylar için `DEPLOYMENT.md` dosyasına bakın.

**ÖNEMLİ:** `.pem` dosyaları GitHub'a gönderilmemelidir!
