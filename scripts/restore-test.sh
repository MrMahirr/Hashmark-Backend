#!/bin/bash
set -euo pipefail

# ============================================
# Hashmark Yedek Geri Yükleme Testi
# ============================================
# Bir yedeği geçici bir Postgres konteynerine yükler, süreyi ölçer ve tabloları doğrular.
# Üretim veritabanına DOKUNMAZ; işi bitince geçici konteyner silinir.
#
# Kullanım:
#   ./scripts/restore-test.sh                       # en yeni yedek (backups/)
#   ./scripts/restore-test.sh backups/dosya.sql.gz  # belirli yedek
#
# Çıktıdaki "geri yükleme süresi" RTO'nun ilk gerçek ölçümüdür (DEPLOYMENT.md / plan Bölüm 3.1).
# Üç ayda bir çalıştırın; sonucu kaydedin.
# ============================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_FILE="${1:-$(ls -1t "$BACKEND_DIR"/backups/hashmark_backup_*.sql.gz 2>/dev/null | head -n1 || true)}"
IMAGE="${POSTGRES_IMAGE:-postgres:18-alpine}"
NAME="hashmark_restore_test_$$"
export MSYS_NO_PATHCONV=1

if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
    echo "✗ Yedek bulunamadı. Önce ./scripts/backup-db.sh çalıştırın veya dosya yolu verin." >&2
    exit 1
fi

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "Yedek: $BACKUP_FILE ($BACKUP_SIZE)"

# Bütünlük: gzip bozuk mu?
gzip -t "$BACKUP_FILE"
echo "✓ gzip bütünlüğü tamam"

docker run -d --name "$NAME" -e POSTGRES_PASSWORD=restore_test -e POSTGRES_DB=restore_test "$IMAGE" >/dev/null
for _ in $(seq 1 30); do
    docker exec "$NAME" pg_isready -U postgres -d restore_test >/dev/null 2>&1 && break
    sleep 1
done

START=$(date +%s)
gunzip -c "$BACKUP_FILE" | docker exec -i "$NAME" psql -U postgres -d restore_test -v ON_ERROR_STOP=1 -q >/dev/null
END=$(date +%s)
echo "✓ Geri yükleme süresi: $((END - START)) sn"

echo "--- Doğrulama ---"
TABLES=$(docker exec "$NAME" psql -U postgres -d restore_test -tA -c "SELECT count(*) FROM information_schema.tables WHERE table_schema='public'")
echo "Tablo sayısı: $TABLES"
docker exec "$NAME" psql -U postgres -d restore_test -tA -c \
  "SELECT 'flyway son sürüm: ' || max(version::int) FROM flyway_schema_history WHERE success" || true
docker exec "$NAME" psql -U postgres -d restore_test -tA -F ' : ' -c \
  "SELECT relname, n_live_tup FROM pg_stat_user_tables ORDER BY relname" || true

if [ "$TABLES" -lt 1 ]; then
    echo "✗ Geri yükleme boş çıktı" >&2
    exit 1
fi
echo "✓ Geri yükleme testi başarılı (süre: $((END - START)) sn, yedek: $BACKUP_SIZE)"
