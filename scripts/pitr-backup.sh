#!/bin/bash
set -euo pipefail

# ============================================
# PITR temel yedeği (pgBackRest → şifreli, sunucu dışı S3 deposu). docs/PITR.md
# ============================================
# Kullanım: ./scripts/pitr-backup.sh [full|diff|incr]   (varsayılan: diff; ilk çalıştırma kendiliğinden full olur)
#           ./scripts/pitr-backup.sh setup                (tek seferlik: stanza oluştur + arşiv kontrolü)
# Cron (önerilen):
#   15 3 * * 0   .../scripts/pitr-backup.sh full   >> .../backups/pitr.log 2>&1   # pazar
#   15 3 * * 1-6 .../scripts/pitr-backup.sh diff   >> .../backups/pitr.log 2>&1
# Metrik: backups/metrics/hashmark_pitr.prom (alarmlar: PitrBackupFailed, PitrBackupStale)
# ============================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
CONTAINER="${DB_CONTAINER:-hashmark_postgres}"
TYPE="${1:-diff}"
METRICS_DIR="${METRICS_DIR:-$BACKEND_DIR/backups/metrics}"
METRICS_FILE="$METRICS_DIR/hashmark_pitr.prom"
mkdir -p "$METRICS_DIR"

pgbr() { docker exec "$CONTAINER" pgbackrest "$@"; }

if ! docker exec "$CONTAINER" sh -c '[ -n "${PGBACKREST_REPO1_TYPE:-}" ]'; then
    echo "✗ PITR deposu yapılandırılmamış (pitr.env yok ya da konteyner yeniden oluşturulmadı). docs/PITR.md" >&2
    exit 1
fi

if [ "$TYPE" = "setup" ]; then
    pgbr stanza-create
    pgbr check            # WAL arşivinin depoya gerçekten ulaştığını doğrular (bir WAL dosyası zorlanır)
    echo "✓ Stanza hazır. İlk tam yedek: ./scripts/pitr-backup.sh full"
    exit 0
fi

case "$TYPE" in full|diff|incr) ;; *) echo "✗ Geçersiz tür: $TYPE (full|diff|incr|setup)" >&2; exit 2 ;; esac

# $1: 1 = başarılı, 0 = başarısız. Başarısızlıkta son başarı zamanı korunur.
write_metrics() {
    local ok="$1" last_success tmp
    last_success=$(grep -s '^hashmark_pitr_backup_last_success_timestamp_seconds ' "$METRICS_FILE" | awk '{print $2}' || true)
    [ "$ok" = "1" ] && last_success=$(date +%s)
    tmp="$METRICS_FILE.$$"
    {
        echo "# TYPE hashmark_pitr_backup_last_run_success gauge"
        echo "hashmark_pitr_backup_last_run_success $ok"
        if [ -n "${last_success:-}" ]; then
            echo "# TYPE hashmark_pitr_backup_last_success_timestamp_seconds gauge"
            echo "hashmark_pitr_backup_last_success_timestamp_seconds $last_success"
        fi
        if [ "$ok" = "1" ] && [ -n "${DURATION:-}" ]; then
            echo "# TYPE hashmark_pitr_backup_last_duration_seconds gauge"
            echo "hashmark_pitr_backup_last_duration_seconds $DURATION"
        fi
    } > "$tmp"
    mv "$tmp" "$METRICS_FILE"
}
trap 'write_metrics 0' ERR

START=$(date +%s)
echo "$(date -Is) PITR yedeği başlıyor (tür: $TYPE)"
pgbr backup --type="$TYPE"
DURATION=$(( $(date +%s) - START ))
# Saklama süresi (PGBACKREST_REPO1_RETENTION_FULL gün) dolan yedek ve WAL'lar backup sonunda otomatik silinir.
pgbr info | sed -n '1,40p'
echo "$(date -Is) ✓ PITR yedeği tamam ($TYPE, ${DURATION} sn)"
write_metrics 1
