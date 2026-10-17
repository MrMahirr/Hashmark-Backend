#!/bin/bash
set -euo pipefail

# ============================================
# Hashmark Database Backup Script
# ============================================
# Kullanım: ./scripts/backup-db.sh
# Cron: 0 3 * * * /opt/hashmark/hashmark-backend/scripts/backup-db.sh
# ============================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
BACKUP_DIR="$BACKEND_DIR/backups"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILE="$BACKUP_DIR/hashmark_backup_${TIMESTAMP}.sql.gz"

# Backup dizini oluştur
mkdir -p "$BACKUP_DIR"

# node-exporter textfile toplayıcısı için metrik dosyası (monitoring/alerts.yml: BackupFailed, BackupStale).
METRICS_DIR="$BACKUP_DIR/metrics"
METRICS_FILE="$METRICS_DIR/hashmark_backup.prom"
mkdir -p "$METRICS_DIR"

# $1: 1 = başarılı, 0 = başarısız. Başarısızlıkta son başarı zamanı korunur.
write_metrics() {
    local ok="$1" last_success tmp
    last_success=$(grep -s '^hashmark_backup_last_success_timestamp_seconds ' "$METRICS_FILE" | awk '{print $2}' || true)
    [ "$ok" = "1" ] && last_success=$(date +%s)
    tmp="$METRICS_FILE.$$"
    {
        echo "# TYPE hashmark_backup_last_run_success gauge"
        echo "hashmark_backup_last_run_success $ok"
        if [ -n "${last_success:-}" ]; then
            echo "# TYPE hashmark_backup_last_success_timestamp_seconds gauge"
            echo "hashmark_backup_last_success_timestamp_seconds $last_success"
        fi
        if [ "$ok" = "1" ] && [ -n "${BACKUP_BYTES:-}" ]; then
            echo "# TYPE hashmark_backup_last_size_bytes gauge"
            echo "hashmark_backup_last_size_bytes $BACKUP_BYTES"
        fi
    } > "$tmp"
    mv "$tmp" "$METRICS_FILE"
}
trap 'write_metrics 0' ERR

# .env dosyasından değişkenleri oku
if [ -f "$BACKEND_DIR/.env" ]; then
    export $(grep -v '^#' "$BACKEND_DIR/.env" | xargs)
fi

DB_NAME="${POSTGRES_DB:-hashmark}"
DB_USER="${POSTGRES_USER:-hashmark}"
DB_CONTAINER="${DB_CONTAINER:-hashmark_postgres}"

echo "Veritabanı yedeği alınıyor: $DB_NAME"
echo "Hedef: $BACKUP_FILE"

# Docker üzerinden pg_dump
docker exec "$DB_CONTAINER" pg_dump \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    --no-owner \
    --no-privileges \
    | gzip > "$BACKUP_FILE"

# Boyut kontrolü: boş/çok küçük yedek başarı sayılmaz
BACKUP_BYTES=$(wc -c < "$BACKUP_FILE" | tr -d ' ')
if [ "$BACKUP_BYTES" -lt 1024 ]; then
    echo "✗ Yedek şüpheli derecede küçük ($BACKUP_BYTES bayt)" >&2
    false
fi
BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "✓ Yedek alındı: $BACKUP_FILE ($BACKUP_SIZE)"

# 30 günden eski yedekleri sil
find "$BACKUP_DIR" -name "hashmark_backup_*.sql.gz" -mtime +30 -delete
echo "✓ 30 günden eski yedekler temizlendi"

write_metrics 1
