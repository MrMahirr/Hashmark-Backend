# Hashmark — Quick Deploy

Sıfırdan VDS üzerinde hızlı deployment. Detaylar için `DEPLOYMENT.md` dosyasına bakın.

```bash
# 1. Docker kur
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER && newgrp docker

# 2. Firewall
sudo ufw allow 22/tcp && sudo ufw allow 80/tcp && sudo ufw allow 443/tcp
sudo ufw --force enable

# 3. Clone
sudo mkdir -p /opt/hashmark && sudo chown $USER:$USER /opt/hashmark
cd /opt/hashmark
git clone https://github.com/MrMahirr/Hashmark-Frontend.git hashmark-frontend
git clone https://github.com/MrMahirr/Hashmark-Backend.git hashmark-backend

# 4. Environment
cd hashmark-backend
cp .env.example .env
nano .env    # Tüm değerleri doldurun

# 5. SSL (geçici self-signed)
mkdir -p nginx/ssl nginx/certbot/www
openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout nginx/ssl/privkey.pem -out nginx/ssl/fullchain.pem \
  -subj "/CN=hashmark.yunusemremahir.com.tr"

# 6. Başlat
docker compose up -d --build

# 7. Kontrol
docker compose ps
docker compose logs -f

# 8. Let's Encrypt (DNS yayıldıktan sonra)
docker compose stop nginx
sudo apt install -y certbot
sudo certbot certonly --standalone -d hashmark.yunusemremahir.com.tr
sudo cp /etc/letsencrypt/live/hashmark.yunusemremahir.com.tr/fullchain.pem nginx/ssl/fullchain.pem
sudo cp /etc/letsencrypt/live/hashmark.yunusemremahir.com.tr/privkey.pem nginx/ssl/privkey.pem
sudo chown $USER:$USER nginx/ssl/*.pem
docker compose up -d nginx

# 9. Backup cron
chmod +x scripts/backup-db.sh
(crontab -l 2>/dev/null; echo "0 3 * * * /opt/hashmark/hashmark-backend/scripts/backup-db.sh >> /opt/hashmark/hashmark-backend/backups/backup.log 2>&1") | crontab -
```

**Production URL:** https://hashmark.yunusemremahir.com.tr
