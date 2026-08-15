# Hashmark — Production Deployment Guide

Bu döküman, Hashmark uygulamasını sıfırdan bir Linux VDS üzerinde production ortamında çalıştırmak için gerekli tüm adımları içerir.

---

## Mimari

```
Internet
   │
   ▼
https://hashmark.yunusemremahir.com.tr
   │
   ▼
┌──────────────────────────────────────┐
│         Nginx Reverse Proxy          │
│         Ports: 80, 443               │
│  /       → frontend:3000             │
│  /api/v1 → backend:8080 (strip)      │
└────────┬───────────────┬─────────────┘
         │               │
   ┌─────▼─────┐   ┌─────▼──────────┐
   │  Frontend  │   │    Backend     │
   │  :3000     │   │    :8080       │
   └────────────┘   └──┬──────────┬──┘
                       │          │
              ┌────────▼──┐  ┌───▼──────┐
              │ PostgreSQL │  │  Redis   │
              │ :5432      │  │  :6379   │
              └────────────┘  └──────────┘
```

**Tüm servisler Docker internal network üzerindedir. Sadece Nginx 80/443 portlarını host'a açar.**

---

## 1. VDS Gereksinimleri

| Kaynak | Minimum | Önerilen |
|--------|---------|----------|
| CPU | 2 vCPU | 4 vCPU |
| RAM | 2 GB | 4 GB |
| Disk | 20 GB SSD | 40 GB SSD |
| OS | Ubuntu 22.04 LTS | Ubuntu 24.04 LTS |

---

## 2. Ubuntu İlk Kurulum

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y curl git wget ufw
```

---

## 3. Docker Kurulumu

```bash
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh
rm get-docker.sh

sudo usermod -aG docker $USER
newgrp docker

# Doğrulama
docker --version
docker compose version
```

---

## 4. Firewall (UFW)

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 80/tcp    # HTTP
sudo ufw allow 443/tcp   # HTTPS
sudo ufw enable
```

> **ÖNEMLİ:** 5432 (PostgreSQL), 6379 (Redis), 8080 (Backend), 3000 (Frontend) portları **kapalı** kalmalıdır.

---

## 5. Repository Clone

```bash
sudo mkdir -p /opt/hashmark
sudo chown $USER:$USER /opt/hashmark
cd /opt/hashmark

git clone https://github.com/MrMahirr/Hashmark-Frontend.git hashmark-frontend
git clone https://github.com/MrMahirr/Hashmark-Backend.git hashmark-backend
```

Dizin yapısı:
```
/opt/hashmark/
├── hashmark-frontend/          ← Frontend repo
└── hashmark-backend/           ← Backend repo (deployment dosyaları burada)
    ├── docker-compose.yml
    ├── nginx/
    │   ├── nginx.conf
    │   └── ssl/
    ├── scripts/
    │   ├── deploy.sh
    │   └── backup-db.sh
    ├── .env.example
    ├── Dockerfile
    ├── DEPLOYMENT.md           ← Bu dosya
    └── QUICK_DEPLOY.md
```

---

## 6. Environment Variables (.env)

```bash
cd /opt/hashmark/hashmark-backend
cp .env.example .env
nano .env
```

### Doldurulması gereken değişkenler:

| Değişken | Açıklama | Örnek |
|----------|----------|-------|
| `POSTGRES_PASSWORD` | DB şifresi (güçlü) | `X7k#mP9$qR2wL5!n` |
| `DATABASE_PASSWORD` | Aynı şifre | `X7k#mP9$qR2wL5!n` |
| `GITHUB_CLIENT_ID` | GitHub OAuth Client ID | `Ov23li...` |
| `GITHUB_CLIENT_SECRET` | GitHub OAuth Client Secret | `e10a44...` |
| `GITHUB_CALLBACK_URL` | OAuth callback | `https://hashmark.yunusemremahir.com.tr/auth/callback` |
| `JWT_SECRET` | JWT imzalama secret'ı | `openssl rand -base64 64` |
| `ENCRYPTION_SECRET` | AES key (32 hex) | `openssl rand -hex 16` |
| `CORS_ALLOWED_ORIGINS` | Frontend origin | `https://hashmark.yunusemremahir.com.tr` |
| `NEXT_PUBLIC_API_URL` | Frontend API URL | `https://hashmark.yunusemremahir.com.tr/api/v1` |
| `NEXT_PUBLIC_APP_URL` | Frontend URL | `https://hashmark.yunusemremahir.com.tr` |
| `RESEND_API_KEY` | E-posta API key | Resend dashboard'dan |
| `RESEND_FROM_EMAIL` | Gönderici e-posta | `noreply@hashmark.dev` |

### Secret üretimi:

```bash
# JWT Secret
openssl rand -base64 64

# Encryption Secret
openssl rand -hex 16
```

---

## 7. DNS Ayarı

| Tür | Ad | Değer | TTL |
|-----|----|----|-----|
| A | hashmark.yunusemremahir.com.tr | VDS_IP_ADRESI | 300 |

```bash
# Doğrulama
dig +short hashmark.yunusemremahir.com.tr
```

---

## 8. SSL Sertifikası

### Adım 1: Geçici self-signed sertifika (Nginx'in ayağa kalkması için)

```bash
cd /opt/hashmark/hashmark-backend
mkdir -p nginx/ssl nginx/certbot/www

openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout nginx/ssl/privkey.pem \
  -out nginx/ssl/fullchain.pem \
  -subj "/CN=hashmark.yunusemremahir.com.tr"
```

### Adım 2: İlk deployment

```bash
docker compose up -d --build
```

### Adım 3: Let's Encrypt sertifikası (DNS yayıldıktan sonra)

```bash
# Nginx'i durdur
docker compose stop nginx

# Certbot ile sertifika al
sudo apt install -y certbot
sudo certbot certonly --standalone \
  -d hashmark.yunusemremahir.com.tr \
  --agree-tos \
  --email your-email@example.com

# Sertifikaları kopyala
sudo cp /etc/letsencrypt/live/hashmark.yunusemremahir.com.tr/fullchain.pem nginx/ssl/fullchain.pem
sudo cp /etc/letsencrypt/live/hashmark.yunusemremahir.com.tr/privkey.pem nginx/ssl/privkey.pem
sudo chown $USER:$USER nginx/ssl/*.pem

# Nginx'i başlat
docker compose up -d nginx
```

### Adım 4: Otomatik yenileme

```bash
sudo crontab -e
# Ekle:
0 3 1 * * certbot renew --pre-hook "docker compose -f /opt/hashmark/hashmark-backend/docker-compose.yml stop nginx" --post-hook "cp /etc/letsencrypt/live/hashmark.yunusemremahir.com.tr/fullchain.pem /opt/hashmark/hashmark-backend/nginx/ssl/fullchain.pem && cp /etc/letsencrypt/live/hashmark.yunusemremahir.com.tr/privkey.pem /opt/hashmark/hashmark-backend/nginx/ssl/privkey.pem && docker compose -f /opt/hashmark/hashmark-backend/docker-compose.yml up -d nginx"
```

---

## 9. GitHub OAuth App Ayarları

1. https://github.com/settings/developers → OAuth Apps
2. **Homepage URL:** `https://hashmark.yunusemremahir.com.tr`
3. **Authorization callback URL:** `https://hashmark.yunusemremahir.com.tr/auth/callback`

---

## 10. İlk Deployment

```bash
cd /opt/hashmark/hashmark-backend

# SSL (yukarıda yapılmadıysa)
mkdir -p nginx/ssl nginx/certbot/www
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout nginx/ssl/privkey.pem -out nginx/ssl/fullchain.pem \
  -subj "/CN=hashmark.yunusemremahir.com.tr"

# Sistemi başlat
docker compose up -d --build

# Logları izle
docker compose logs -f
```

İlk build 5–10 dakika sürebilir.

---

## 11. Log Görüntüleme

```bash
cd /opt/hashmark/hashmark-backend

# Tüm servislerin logları
docker compose logs -f

# Belirli servis
docker compose logs -f backend
docker compose logs -f frontend
docker compose logs -f nginx

# Son 100 satır
docker compose logs --tail=100 backend
```

---

## 12. Restart

```bash
cd /opt/hashmark/hashmark-backend

# Tüm servisleri
docker compose restart

# Belirli servis
docker compose restart backend
```

---

## 13. Update (Güncelleme)

```bash
cd /opt/hashmark/hashmark-backend

# Deploy script ile
chmod +x scripts/deploy.sh
./scripts/deploy.sh

# Veya manuel
git pull
cd ../hashmark-frontend && git pull && cd ../hashmark-backend
docker compose up -d --build
```

---

## 14. Database Migration

Flyway migration'ları backend başladığında **otomatik** çalışır.

```bash
# Migration durumunu kontrol et
docker compose logs backend | grep -i flyway
```

---

## 15. Database Backup

```bash
# Manuel backup
chmod +x scripts/backup-db.sh
./scripts/backup-db.sh

# Otomatik günlük backup
crontab -e
# Ekle:
0 3 * * * /opt/hashmark/hashmark-backend/scripts/backup-db.sh >> /opt/hashmark/hashmark-backend/backups/backup.log 2>&1
```

### Geri yükleme:

```bash
gunzip < backups/hashmark_backup_YYYYMMDD_HHMMSS.sql.gz | \
  docker exec -i hashmark_postgres psql -U hashmark -d hashmark
```

---

## 16. Rollback

```bash
cd /opt/hashmark/hashmark-backend
git log --oneline -5
git checkout <commit-hash>
docker compose up -d --build backend
```

---

## 17. Troubleshooting

### Container durumları
```bash
docker compose ps
docker inspect --format='{{.State.Health.Status}}' hashmark_backend
```

### Backend başlamıyorsa
```bash
docker compose logs backend
# Flyway hatası → Migration dosyalarını kontrol edin
# DB bağlantı hatası → .env'deki POSTGRES_PASSWORD'u kontrol edin
```

### Frontend build hatası
```bash
docker compose logs frontend
# NEXT_PUBLIC_API_URL doğru mu → .env kontrol edin
```

### Nginx 502 Bad Gateway
```bash
docker compose ps   # Backend/Frontend çalışıyor mu?
docker compose logs nginx
```

### Database'e doğrudan bağlanma
```bash
docker exec -it hashmark_postgres psql -U hashmark -d hashmark
```

### Disk alanı
```bash
docker system df
docker system prune -a --volumes   # DİKKAT: Tüm kullanılmayan image'ları siler
```
