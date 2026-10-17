#!/bin/bash
set -euo pipefail

# ============================================
# PITR geri yükleme tatbikatı (docs/PITR.md). Üç ayda bir (cron + RestoreDrillOverdue alarmı).
# ============================================
# Depodaki yedeği + WAL'ları GEÇİCİ bir konteynere geri yükler, açar, doğrular, süreyi ölçer ve siler.
# Üretim veritabanına ve volume'una DOKUNMAZ (yalnızca depoyu okur).
#
# Kullanım:
#   ./scripts/pitr-restore-test.sh                              # en son ana kadar (latest)
#   PITR_DOCKER_NETWORK=<ağ> ...                                 # depo yerel bir konteynerdeyse
#   ./scripts/pitr-restore-test.sh "2026-10-17 14:30:00+03"     # belirli ana (PITR)
# Cron (önerilen): 0 5 1 1,4,7,10 * .../scripts/pitr-restore-test.sh >> .../backups/restore-drills.log 2>&1
# Sonuç: backups/restore-drills.log (satır başına bir tatbikat) + backups/metrics/hashmark_restore_drill.prom
# ============================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
SOURCE="${DB_CONTAINER:-hashmark_postgres}"
IMAGE="${PITR_IMAGE:-hashmark-postgres:18}"
TARGET="${1:-}"
NAME="hashmark_pitr_drill_$$"
VOLUME="hashmark_pitr_drill_$$"
METRICS_DIR="${METRICS_DIR:-$BACKEND_DIR/backups/metrics}"
LOG_FILE="${DRILL_LOG:-$BACKEND_DIR/backups/restore-drills.log}"
DB_USER="${POSTGRES_USER:-hashmark}"
DB_NAME="${POSTGRES_DB:-hashmark}"
export MSYS_NO_PATHCONV=1
mkdir -p "$METRICS_DIR" "$(dirname "$LOG_FILE")"

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker volume rm "$VOLUME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

record() { # $1 ok, $2 sn, $3 açıklama
    local tmp="$METRICS_DIR/hashmark_restore_drill.prom.$$" last
    last=$(grep -s '^hashmark_restore_drill_last_success_timestamp_seconds ' "$METRICS_DIR/hashmark_restore_drill.prom" | awk '{print $2}' || true)
    [ "$1" = "1" ] && last=$(date +%s)
    {
        echo "# TYPE hashmark_restore_drill_last_run_success gauge"
        echo "hashmark_restore_drill_last_run_success $1"
        [ -n "${last:-}" ] && echo "hashmark_restore_drill_last_success_timestamp_seconds $last"
        [ "$1" = "1" ] && echo "hashmark_restore_drill_last_duration_seconds $2"
    } > "$tmp"
    mv "$tmp" "$METRICS_DIR/hashmark_restore_drill.prom"
    echo "$(date -Is) ok=$1 sure_sn=$2 hedef=${TARGET:-latest} $3" >> "$LOG_FILE"
}
trap 'record 0 0 "HATA (satır $LINENO)"' ERR

# Depo ayarları çalışan konteynerden okunur (pitr.env + compose ortamı); sırlar diske yazılmaz.
ENV_ARGS=()
while IFS= read -r line; do
    ENV_ARGS+=(-e "$line")
done < <(docker exec "$SOURCE" env | grep '^PGBACKREST_')
if ! printf '%s\n' "${ENV_ARGS[@]}" | grep -q 'PGBACKREST_REPO1_TYPE='; then
    echo "✗ PITR deposu yapılandırılmamış ($SOURCE). docs/PITR.md" >&2
    exit 1
fi

# Depo yerel ağdaysa (ör. MinIO konteyneri) geçici konteynerler o ağa bağlanır
NET_ARGS=()
[ -n "${PITR_DOCKER_NETWORK:-}" ] && NET_ARGS=(--network "$PITR_DOCKER_NETWORK")

RESTORE_ARGS=(--delta)
if [ -n "$TARGET" ]; then
    RESTORE_ARGS+=(--type=time "--target=$TARGET" --target-action=promote)
fi

START=$(date +%s)
docker volume create "$VOLUME" >/dev/null
# 1) Geri yükle (postgres çalışmıyorken, boş volume'a)
docker run --rm --init "${NET_ARGS[@]}" "${ENV_ARGS[@]}" -v "$VOLUME:/var/lib/postgresql" --user postgres --entrypoint sh "$IMAGE" -c \
    'mkdir -p /var/lib/postgresql/18/docker && chmod 700 /var/lib/postgresql/18/docker && exec pgbackrest restore "$@"' \
    sh "${RESTORE_ARGS[@]}"
# 2) Aç: arşive yazmasın (tatbikat kopyası üretim deposunu kirletmesin), recovery hedefe kadar WAL çeker
docker run -d --init --name "$NAME" "${NET_ARGS[@]}" "${ENV_ARGS[@]}" -v "$VOLUME:/var/lib/postgresql" "$IMAGE" \
    postgres -c archive_mode=off -c shared_preload_libraries=pg_stat_statements >/dev/null
for _ in $(seq 1 120); do
    if docker exec "$NAME" pg_isready -U "$DB_USER" -d "$DB_NAME" >/dev/null 2>&1 \
        && [ "$(docker exec "$NAME" psql -U "$DB_USER" -d "$DB_NAME" -tAc 'SELECT pg_is_in_recovery()' 2>/dev/null)" = "f" ]; then
        break
    fi
    sleep 1
done
DURATION=$(( $(date +%s) - START ))

echo "--- Doğrulama ---"
SUMMARY=$(docker exec "$NAME" psql -U "$DB_USER" -d "$DB_NAME" -tA -v ON_ERROR_STOP=1 -c \
  "SELECT 'flyway=' || max(version::int) FROM flyway_schema_history WHERE success" \
  -c "SELECT 'users=' || count(*) FROM users" \
  -c "SELECT 'debts=' || count(*) FROM debts" \
  -c "SELECT 'son_tarama=' || coalesce(max(finished_at)::text, '-') FROM scan_jobs" | paste -sd' ')
echo "$SUMMARY"
echo "$SUMMARY" | grep -q 'flyway=' || { echo "✗ Şema doğrulanamadı" >&2; false; }

echo "✓ PITR tatbikatı başarılı: ${DURATION} sn (hedef: ${TARGET:-latest})"
record 1 "$DURATION" "$SUMMARY"
