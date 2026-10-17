#!/bin/bash
set -euo pipefail

# ============================================================
# Hashmark yük testi koşucusu (yerel Docker, ASLA üretimde değil)
# ============================================================
# Kullanım: ./loadtest/run.sh login|dashboard|scan|explain
#
# Küçük bir VDS'i (2 vCPU / 2 GB) CPU/bellek sınırlarıyla taklit eder:
#   backend 1 CPU + 1 GB, postgres 1 CPU + 512 MB. k6 ve sahte GitHub sınırsızdır (istemci darboğaz olmasın).
# Ortam: WARMUP (sn, 0), PROFILE (0/1), DURATION (60s), BACKEND_CPUS (1), VUS (senaryo varsayılanı). Sonuçlar loadtest/results/<tarih>-<senaryo>/ altına yazılır. Sonuçlar GERÇEK VDS'i değil, bu sınırlı
# Docker ortamını yansıtır; kapasite iddiası için üretimle aynı donanımda tekrar edin.
# ============================================================

SCENARIO="${1:?Kullanım: run.sh login|dashboard|scan}"
DURATION="${DURATION:-60s}"
BACKEND_CPUS="${BACKEND_CPUS:-1}"
WARMUP="${WARMUP:-0}"     # sn: ölçümden önce aynı senaryoyu ısınma için çalıştırır (JIT ısınması hariç tutulur)
PROFILE="${PROFILE:-0}"   # 1: JFR kaydı al ve özetle (CPU profili)
VUS="${VUS:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(dirname "$SCRIPT_DIR")"
OUT="$SCRIPT_DIR/results/$(date +%Y%m%d-%H%M%S)-$SCENARIO"
P="hmlt"                      # kaynak adı öneki
JWT_SECRET="loadtest-secret-loadtest-secret-loadtest-secret-1234"
export MSYS_NO_PATHCONV=1
# Windows'ta (Git Bash) docker.exe yerel yol ister
dpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi; }
LT_MOUNT="$(dpath "$SCRIPT_DIR")"

mkdir -p "$OUT"
cleanup() {
    [ -n "${STATS_PID:-}" ] && kill "$STATS_PID" 2>/dev/null || true
    docker rm -f ${P}-app ${P}-pg ${P}-mock ${P}-k6 >/dev/null 2>&1 || true
    docker network rm ${P}-net >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

echo "== İmaj derleniyor"
docker build -q -t ${P}-backend "$(dpath "$BACKEND_DIR")" >/dev/null
docker network create ${P}-net >/dev/null

echo "== PostgreSQL (1 CPU / 512 MB, pg_stat_statements açık)"
docker run -d --name ${P}-pg --network ${P}-net --cpus 1 --memory 512m \
    -e POSTGRES_PASSWORD=pw -e POSTGRES_DB=hashmark -e POSTGRES_USER=hashmark \
    postgres:18-alpine \
    -c shared_preload_libraries=pg_stat_statements -c pg_stat_statements.track=all \
    -c max_connections=100 >/dev/null
for _ in $(seq 1 30); do docker exec ${P}-pg pg_isready -U hashmark -d hashmark >/dev/null 2>&1 && break; sleep 1; done

echo "== Sahte GitHub"
docker run -d --name ${P}-mock --network ${P}-net -v "$LT_MOUNT:/lt:ro" -e LATENCY_MS=150 \
    node:22-alpine node /lt/mock-github.js >/dev/null

# JVM_FLAGS: deney için ek JVM bayrakları. EXTRA_ENV: ek ortam değişkenleri (örn. "FOO=1 BAR=2").
JTO="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError ${JVM_FLAGS:-}"
JFR_ARGS=()
if [ "$PROFILE" = "1" ]; then
    mkdir -p "$OUT/jfr"
    JFR_ARGS=(-v "$(dpath "$OUT/jfr"):/jfr")
    JTO="$JTO -XX:FlightRecorderOptions=stackdepth=512 -XX:StartFlightRecording=filename=/jfr/rec.jfr,settings=profile,dumponexit=true"
fi
EXTRA_ARGS=()
for kv in ${EXTRA_ENV:-}; do EXTRA_ARGS+=(-e "$kv"); done

start_backend() {
    docker rm -f ${P}-app >/dev/null 2>&1 || true
    docker run -d --name ${P}-app --network ${P}-net --cpus "$BACKEND_CPUS" --memory 1g --read-only --tmpfs /tmp \
        -e DATABASE_URL=jdbc:postgresql://${P}-pg:5432/hashmark -e DATABASE_USERNAME=hashmark -e DATABASE_PASSWORD=pw \
        -e GITHUB_CLIENT_ID=x -e GITHUB_CLIENT_SECRET=x -e JWT_SECRET="$JWT_SECRET" \
        -e ENCRYPTION_SECRET=0123456789abcdef0123456789abcdef -e CORS_ALLOWED_ORIGINS=http://localhost:3000 \
        -e GITHUB_OAUTH_URL=http://${P}-mock:9000 -e GITHUB_API_URL=http://${P}-mock:9000 \
        -e RATE_LIMIT_AUTH_PER_MINUTE=1000000 -e RATE_LIMIT_SCAN_PER_MINUTE=1000000 \
        -e SCAN_MAX_CONCURRENT=0 -e "JAVA_TOOL_OPTIONS=$JTO" "${JFR_ARGS[@]}" "${EXTRA_ARGS[@]}" \
        ${P}-backend >/dev/null
    for _ in $(seq 1 40); do
        [ "$(docker inspect -f '{{.State.Health.Status}}' ${P}-app)" = healthy ] && return 0
        sleep 3
    done
    echo "Backend ayağa kalkmadı" >&2; docker logs ${P}-app | tail -20; exit 1
}

echo "== Backend (Flyway şemayı kurar)"
start_backend

echo "== Tohum verisi (200 kullanıcı, 600 depo, 300.000 borç)"
docker exec -i ${P}-pg psql -U hashmark -d hashmark -v ON_ERROR_STOP=1 -q < "$SCRIPT_DIR/seed.sql" >/dev/null
docker exec ${P}-pg psql -U hashmark -d hashmark -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null
docker exec ${P}-pg psql -U hashmark -d hashmark -c "SELECT pg_stat_statements_reset()" >/dev/null

if [ "$SCENARIO" = "explain" ]; then
    docker exec -i ${P}-pg psql -U hashmark -d hashmark -f - < "$SCRIPT_DIR/explain.sql" > "$OUT/explain.txt" 2>&1
    echo "Sonuçlar: $OUT/explain.txt"
    exit 0
fi

# Kaynak kullanımı örnekleme (2 sn)
( while true; do docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' ${P}-app ${P}-pg 2>/dev/null >> "$OUT/docker-stats.txt" || true; sleep 2; done ) &
STATS_PID=$!

if [ "$WARMUP" != "0" ]; then
    echo "== Isınma: ${WARMUP}s (ölçülmez)"
    docker run --rm --network ${P}-net -v "$LT_MOUNT:/lt:ro" \
        -e BASE_URL=http://${P}-app:8080 -e JWT_SECRET="$JWT_SECRET" -e SCENARIO="$SCENARIO" -e DURATION="${WARMUP}s" ${VUS:+-e VUS=$VUS} \
        grafana/k6:0.54.0 run --quiet /lt/k6.js >/dev/null 2>&1 || true
    sleep 5
    docker exec ${P}-pg psql -U hashmark -d hashmark -c "SELECT pg_stat_statements_reset()" >/dev/null
fi

echo "== k6: $SCENARIO ($DURATION, backend $BACKEND_CPUS CPU, VUS=${VUS:-varsayılan})"
echo "scenario=$SCENARIO duration=$DURATION warmup=$WARMUP backend_cpus=$BACKEND_CPUS vus=${VUS:-default} jvm_flags=${JVM_FLAGS:-} extra_env=${EXTRA_ENV:-}" > "$OUT/params.txt"
docker run --rm --name ${P}-k6 --network ${P}-net -v "$LT_MOUNT:/lt:ro" \
    -e BASE_URL=http://${P}-app:8080 -e JWT_SECRET="$JWT_SECRET" -e SCENARIO="$SCENARIO" -e DURATION="$DURATION" ${VUS:+-e VUS=$VUS} \
    grafana/k6:0.54.0 run /lt/k6.js 2>&1 | tee "$OUT/k6.txt" || true

kill "$STATS_PID" 2>/dev/null || true; STATS_PID=""

if [ "$PROFILE" = "1" ]; then
    echo "== JFR kaydı kapatılıyor ve özetleniyor"
    docker stop -t 30 ${P}-app >/dev/null
    JDK=eclipse-temurin:25-jdk
    docker run --rm -v "$(dpath "$OUT/jfr"):/jfr" "$JDK" sh -c '
        jfr view hot-methods /jfr/rec.jfr > /jfr/hot-methods.txt
        jfr print --events jdk.ExecutionSample --stack-depth 512 /jfr/rec.jfr > /jfr/samples.txt
        jfr view allocation-by-class /jfr/rec.jfr > /jfr/allocation-by-class.txt
        jfr view gc /jfr/rec.jfr > /jfr/gc.txt 2>&1 || true' || true
    echo "Profil: $OUT/jfr"
    exit 0
fi

echo "== Metrik ve sorgu anlık görüntüleri"
docker exec ${P}-app curl -s localhost:8081/actuator/prometheus > "$OUT/prometheus.txt"
grep -E '^(hikaricp_connections_(max|active|pending|timeout_total|acquire_seconds_max)|hashmark_scan_queue_pending|hashmark_scan_queue_oldest|process_cpu_usage|jvm_memory_used_bytes\{.*area="heap")' "$OUT/prometheus.txt" > "$OUT/prometheus-summary.txt" || true
docker exec ${P}-pg psql -U hashmark -d hashmark -x -c \
    "SELECT calls, round(total_exec_time::numeric,1) AS total_ms, round(mean_exec_time::numeric,2) AS mean_ms, rows, left(query,160) AS query
       FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC LIMIT 10" > "$OUT/top-queries-by-total.txt"
docker exec ${P}-pg psql -U hashmark -d hashmark -x -c \
    "SELECT calls, round(mean_exec_time::numeric,2) AS mean_ms, round(max_exec_time::numeric,1) AS max_ms, rows, left(query,160) AS query
       FROM pg_stat_statements WHERE query NOT ILIKE '%pg_stat_statements%' ORDER BY mean_exec_time DESC LIMIT 10" > "$OUT/top-queries-by-mean.txt"
docker exec ${P}-pg psql -U hashmark -d hashmark -tA -c "SELECT 'scan_jobs PENDING: ' || count(*) FROM scan_jobs WHERE status='PENDING'" > "$OUT/db-state.txt"
docker logs ${P}-app 2>&1 | grep -c '"level":"ERROR"' | sed 's/^/backend ERROR log satırı: /' >> "$OUT/db-state.txt" || true

echo "Sonuçlar: $OUT"
