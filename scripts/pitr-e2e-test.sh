#!/bin/bash
set -euo pipefail

# ============================================
# PITR uçtan uca testi (yerel, üretime dokunmaz). docs/PITR.md → "Doğrulama"
# ============================================
# TLS'li MinIO (S3 uyumlu) + hashmark-postgres imajı ile: stanza → tam yedek → T1'e kadar veri → T1 sonrası veri
# → T1'e PITR geri yükleme (scripts/pitr-restore-test.sh) → T1 sonrası verinin GELMEDİĞİNİ, öncesinin geldiğini
# doğrular; ardından "latest" geri yüklemede hepsinin geldiğini. Her şey geçici; sonunda silinir.
# Kullanım: ./scripts/pitr-e2e-test.sh
# ============================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
export MSYS_NO_PATHCONV=1
NET="hashmark_pitr_e2e"
SRC="hashmark_pitr_e2e_pg"
MINIO="pitr-e2e-minio"   # S3 Host başlığında alt çizgi geçersiz
WORK="$(mktemp -d)"
IMAGE="hashmark-postgres:18"
# MinIO Ekim 2025'te Docker Hub'daki topluluk imajlarını yayından kaldırdı; önbellekteki ya da kendi aynanızdaki
# imajı verin. mc istemcisi aynı imajın içinden kullanılır.
MINIO_IMAGE="${PITR_E2E_MINIO_IMAGE:-minio/minio:RELEASE.2025-09-07T16-13-09Z}"

cleanup() {
    docker rm -f "$SRC" "$MINIO" >/dev/null 2>&1 || true
    docker volume rm "${SRC}_data" >/dev/null 2>&1 || true
    docker network rm "$NET" >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
[ -n "${KEEP:-}" ] || trap cleanup EXIT
cleanup; WORK="$(mktemp -d)"
# Docker'a verilen host yolları (Windows/Git Bash'te C:/... biçimine çevrilir; Linux'ta aynen kalır)
host_path() { cygpath -m "$1" 2>/dev/null || echo "$1"; }

docker build -q -t "$IMAGE" "$(host_path "$BACKEND_DIR/postgres")" >/dev/null
docker network create "$NET" >/dev/null

# --- S3 (MinIO, öz imzalı TLS: pgBackRest S3'e yalnızca HTTPS ile bağlanır)
mkdir -p "$WORK/certs"
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$MINIO" -addext "subjectAltName=DNS:$MINIO" \
    -keyout "$(host_path "$WORK/certs/private.key")" -out "$(host_path "$WORK/certs/public.crt")" 2>/dev/null
chmod 644 "$WORK/certs/private.key"
docker run -d --name "$MINIO" --network "$NET" -e MINIO_ROOT_USER=e2eaccess -e MINIO_ROOT_PASSWORD=e2esecret123 \
    -v "$(host_path "$WORK/certs"):/certs:ro" "$MINIO_IMAGE" server /data --certs-dir /certs >/dev/null
for _ in $(seq 1 30); do
    docker run --rm --network "$NET" --entrypoint sh "$MINIO_IMAGE" -c \
        "mc alias set --insecure e2e https://$MINIO:9000 e2eaccess e2esecret123 >/dev/null 2>&1 && mc mb --insecure -p e2e/hashmark-pitr >/dev/null 2>&1" \
        && break
    sleep 1
done

cat > "$WORK/pitr.env" <<EOF
PGBACKREST_REPO1_TYPE=s3
PGBACKREST_REPO1_S3_ENDPOINT=$MINIO
PGBACKREST_REPO1_STORAGE_PORT=9000
PGBACKREST_REPO1_STORAGE_VERIFY_TLS=n
PGBACKREST_REPO1_S3_REGION=us-east-1
PGBACKREST_REPO1_S3_BUCKET=hashmark-pitr
PGBACKREST_REPO1_S3_URI_STYLE=path
PGBACKREST_REPO1_PATH=/hashmark
PGBACKREST_REPO1_S3_KEY=e2eaccess
PGBACKREST_REPO1_S3_KEY_SECRET=e2esecret123
PGBACKREST_REPO1_CIPHER_PASS=e2e-cipher-pass-not-for-production
EOF

# --- Kaynak Postgres: compose ile aynı PITR ayarları
docker run -d --init --name "$SRC" --network "$NET" -v "${SRC}_data:/var/lib/postgresql" --env-file "$(host_path "$WORK/pitr.env")" \
    -e POSTGRES_USER=hashmark -e POSTGRES_PASSWORD=e2e -e POSTGRES_DB=hashmark \
    -e PGBACKREST_STANZA=hashmark -e PGBACKREST_PG1_PATH=/var/lib/postgresql/18/docker \
    -e PGBACKREST_PG1_USER=hashmark -e PGBACKREST_PG1_DATABASE=hashmark \
    -e PGBACKREST_ARCHIVE_ASYNC=y -e PGBACKREST_SPOOL_PATH=/var/spool/pgbackrest -e PGBACKREST_ARCHIVE_PUSH_QUEUE_MAX=2GiB \
    -e PGBACKREST_COMPRESS_TYPE=zst -e PGBACKREST_REPO1_CIPHER_TYPE=aes-256-cbc \
    -e PGBACKREST_REPO1_RETENTION_FULL_TYPE=time -e PGBACKREST_REPO1_RETENTION_FULL=14 \
    -e PGBACKREST_LOG_LEVEL_CONSOLE=info -e PGBACKREST_LOG_LEVEL_FILE=off \
    "$IMAGE" postgres -c archive_mode=on -c "archive_command=/usr/local/bin/pgbackrest-archive-push.sh %p" -c archive_timeout=60 >/dev/null
# İmajın ilk kurulumu geçici bir sunucu açıp kapatır; asıl sunucuyu bekle
for _ in $(seq 1 90); do
    docker logs "$SRC" 2>&1 | grep -q 'init process complete'         && docker exec "$SRC" psql -U hashmark -d hashmark -tAc 'SELECT 1' >/dev/null 2>&1 && break
    sleep 1
done

PSQL=(docker exec -i "$SRC" psql -U hashmark -d hashmark -v ON_ERROR_STOP=1 -q)
MIG_DIR="$BACKEND_DIR/src/main/resources/db/migration"
for v in $(seq 1 "$(ls "$MIG_DIR" | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)"); do
    "${PSQL[@]}" < "$(ls "$MIG_DIR"/V"$v"__*.sql)" 2>&1 | grep -v -i 'notice\|warning' || true
done
"${PSQL[@]}" -c "CREATE TABLE flyway_schema_history (version varchar(50), success boolean)" \
    -c "INSERT INTO flyway_schema_history SELECT generate_series(1, 11)::text, true"

DB_CONTAINER="$SRC" METRICS_DIR="$WORK/metrics" "$SCRIPT_DIR/pitr-backup.sh" setup
DB_CONTAINER="$SRC" METRICS_DIR="$WORK/metrics" "$SCRIPT_DIR/pitr-backup.sh" full | tail -1
grep -q '^hashmark_pitr_backup_last_run_success 1' "$WORK/metrics/hashmark_pitr.prom"

# --- T1 öncesi 10 kullanıcı, sonrası 5 kullanıcı
"${PSQL[@]}" -c "INSERT INTO users (github_id) SELECT 'before-' || g FROM generate_series(1, 10) g"
sleep 2
T1=$(docker exec "$SRC" psql -U hashmark -d hashmark -tAc "SELECT to_char(now(), 'YYYY-MM-DD HH24:MI:SS.US+00')" )
sleep 2
"${PSQL[@]}" -c "INSERT INTO users (github_id) SELECT 'after-' || g FROM generate_series(1, 5) g"
"${PSQL[@]}" -c "SELECT pg_switch_wal()" >/dev/null
# Asenkron arşivin depoya ulaşmasını bekle
for _ in $(seq 1 30); do
    LAST=$(docker exec "$SRC" psql -U hashmark -d hashmark -tAc "SELECT coalesce(last_archived_wal,'') FROM pg_stat_archiver")
    CUR=$(docker exec "$SRC" psql -U hashmark -d hashmark -tAc "SELECT pg_walfile_name(pg_current_wal_lsn())")
    [ -n "$LAST" ] && [[ "$LAST" < "$CUR" ]] && break
    sleep 1
done
echo "T1 = $T1"

run_drill() { # $1 hedef (boş = latest)
    PITR_IMAGE="$IMAGE" PITR_DOCKER_NETWORK="$NET" DB_CONTAINER="$SRC" METRICS_DIR="$WORK/metrics" \
        DRILL_LOG="$WORK/drills.log" POSTGRES_USER=hashmark POSTGRES_DB=hashmark \
        "$SCRIPT_DIR/pitr-restore-test.sh" ${1:+"$1"} | tail -2
}

run_drill "$T1"
grep -q 'users=10 ' "$WORK/drills.log" || { echo "✗ T1'e geri yükleme 10 kullanıcı vermedi:"; cat "$WORK/drills.log"; exit 1; }
echo "✓ PITR: T1 anına dönüldü (T1 sonrası 5 kayıt yok)"

run_drill ""
grep -q 'users=15 ' "$WORK/drills.log" || { echo "✗ latest geri yükleme 15 kullanıcı vermedi:"; cat "$WORK/drills.log"; exit 1; }
echo "✓ PITR: latest geri yükleme tüm veriyi getirdi"

# Üretim olay prosedürü (docs/PITR.md §3): durdur → mevcut volume'a --delta ile T1'e geri yükle → aç
docker stop "$SRC" >/dev/null
docker run --rm --init --network "$NET" --env-file "$(host_path "$WORK/pitr.env")"     -e PGBACKREST_STANZA=hashmark -e PGBACKREST_PG1_PATH=/var/lib/postgresql/18/docker     -e PGBACKREST_REPO1_CIPHER_TYPE=aes-256-cbc -e PGBACKREST_LOG_LEVEL_CONSOLE=warn     -v "${SRC}_data:/var/lib/postgresql" --user postgres --entrypoint sh "$IMAGE" -c     'exec pgbackrest restore --delta --type=time "--target=$1" --target-action=promote' sh "$T1"
docker start "$SRC" >/dev/null
for _ in $(seq 1 60); do
    [ "$(docker exec "$SRC" psql -U hashmark -d hashmark -tAc 'SELECT NOT pg_is_in_recovery()' 2>/dev/null)" = "t" ] && break
    sleep 1
done
INPLACE=$(docker exec "$SRC" psql -U hashmark -d hashmark -tAc "SELECT count(*) FROM users")
[ "$INPLACE" = "10" ] || { echo "✗ Yerinde geri yükleme 10 yerine $INPLACE kullanıcı verdi"; exit 1; }
echo "✓ Yerinde (--delta) geri yükleme: üretim volume'u T1'e döndü"

# Depodaki nesneler istemci tarafında şifreli mi? Sıkıştırma (zstd) düz metni zaten gizlediği için içerik
# aramak kanıt değildir: her WAL/yedek dosyası OpenSSL biçimli "Salted__" başlığıyla başlamalı (zstd büyüsü değil).
mkdir -p "$WORK/repo"
docker stop "$SRC" >/dev/null   # kopyalama sırasında yeni WAL yazılmasın
docker run --rm --network "$NET" -v "$(host_path "$WORK/repo"):/out" --entrypoint sh "$MINIO_IMAGE" -c \
    "mc alias set --insecure e2e https://$MINIO:9000 e2eaccess e2esecret123 >/dev/null && mc cp --quiet --insecure -r e2e/hashmark-pitr/ /out/" >/dev/null
total=0; enc=0
while IFS= read -r f; do
    total=$((total + 1))
    [ "$(head -c 8 "$f")" = "Salted__" ] && enc=$((enc + 1))
done < <(find "$WORK/repo" -type f \( -name '*.zst' -o -name '*.info' \))
CHECK="$enc/$total"
echo "Şifreli nesne: $CHECK"
case "$CHECK" in 0/*|*/0) echo "✗ Şifreli nesne doğrulanamadı ($CHECK)"; exit 1 ;; esac
[ "${CHECK%/*}" = "${CHECK#*/}" ] || { echo "✗ Şifresiz nesne var ($CHECK)"; exit 1; }
echo "✓ Depodaki tüm WAL/yedek dosyaları istemci tarafında şifreli"
cat "$WORK/drills.log"
echo "✓ PITR uçtan uca testi başarılı"
