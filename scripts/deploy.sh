#!/bin/bash
set -euo pipefail

# ============================================
# Hashmark Deployment Script
# ============================================
# Kullanım: ./scripts/deploy.sh
# Backend repo kök dizininden çalıştırın.
# ============================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
FRONTEND_DIR="$(dirname "$BACKEND_DIR")/hashmark-frontend"

cd "$BACKEND_DIR"

echo "============================================"
echo "  Hashmark Deployment"
echo "  $(date '+%Y-%m-%d %H:%M:%S')"
echo "============================================"
echo ""

# 1. Git pull
echo "[1/5] Git pull (frontend & backend)..."
git pull --ff-only
if [ -d "$FRONTEND_DIR/.git" ]; then
    (cd "$FRONTEND_DIR" && git pull --ff-only)
fi
echo "✓ Git pull tamamlandı"
echo ""

# 2. Env kontrolü
echo "[2/5] Environment kontrolü..."
if [ ! -f ".env" ]; then
    echo "✗ HATA: .env dosyası bulunamadı!"
    echo "  .env.example dosyasını .env olarak kopyalayın ve değerleri doldurun."
    exit 1
fi
echo "✓ .env dosyası mevcut"
echo ""

# 3. Docker build
echo "[3/5] Docker image build..."
docker compose build --no-cache
echo "✓ Docker build tamamlandı"
echo ""

# 4. Container update
echo "[4/5] Container'lar güncelleniyor..."
docker compose up -d
echo "✓ Container'lar başlatıldı"
echo ""

# 5. Health check
echo "[5/5] Health check (60 saniye bekleniyor)..."
sleep 10

RETRIES=10
DELAY=5
HEALTHY=true

for SERVICE in postgres redis backend frontend nginx; do
    echo -n "  $SERVICE: "
    for i in $(seq 1 $RETRIES); do
        STATUS=$(docker inspect --format='{{.State.Health.Status}}' "hashmark_${SERVICE}" 2>/dev/null || echo "unknown")
        if [ "$STATUS" = "healthy" ]; then
            echo "✓ healthy"
            break
        fi
        if [ "$i" -eq "$RETRIES" ]; then
            echo "✗ unhealthy (status: $STATUS)"
            HEALTHY=false
        else
            sleep $DELAY
        fi
    done
done

echo ""
echo "============================================"
if [ "$HEALTHY" = true ]; then
    echo "  ✓ Deployment başarılı!"
else
    echo "  ✗ Bazı servisler sağlıksız!"
    echo "  Logları kontrol edin: docker compose logs"
fi
echo "============================================"
