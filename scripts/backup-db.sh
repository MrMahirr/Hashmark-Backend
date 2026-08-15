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

# .env dosyasından değişkenleri oku
if [ -f "$BACKEND_DIR/.env" ]; then
    export $(grep -v '^#' "$BACKEND_DIR/.env" | xargs)
fi

DB_NAME="${POSTGRES_DB:-hashmark}"
DB_USER="${POSTGRES_USER:-hashmark}"

echo "Veritabanı yedeği alınıyor: $DB_NAME"
echo "Hedef: $BACKUP_FILE"

# Docker üzerinden pg_dump
docker exec hashmark_postgres pg_dump \
    -U "$DB_USER" \
    -d "$DB_NAME" \
    --no-owner \
    --no-privileges \
    | gzip > "$BACKUP_FILE"

# Boyut kontrolü
BACKUP_SIZE=$(du -h "$BACKUP_FILE" | cut -f1)
echo "✓ Yedek alındı: $BACKUP_FILE ($BACKUP_SIZE)"

# 30 günden eski yedekleri sil
find "$BACKUP_DIR" -name "hashmark_backup_*.sql.gz" -mtime +30 -delete
echo "✓ 30 günden eski yedekler temizlendi"
