#!/bin/bash
set -euo pipefail

# pg_stat_statements: en pahalı 10 sorgu (toplam süre) ve en yavaş 10 sorgu (ortalama süre).
# Yavaş bir sorgu bulursanız: EXPLAIN (ANALYZE, BUFFERS) ile planına bakın (loadtest/explain.sql örnek).
# Uzantı ilk kullanımda oluşturulur. Kullanım: ./scripts/pg-top-queries.sh [--reset]
CONTAINER="${DB_CONTAINER:-hashmark_postgres}"
DB_USER="${POSTGRES_USER:-hashmark}"
DB_NAME="${POSTGRES_DB:-hashmark}"
PSQL=(docker exec -i "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1)

"${PSQL[@]}" -q -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null

if [ "${1:-}" = "--reset" ]; then
    "${PSQL[@]}" -q -c "SELECT pg_stat_statements_reset()" >/dev/null
    echo "✓ İstatistikler sıfırlandı"
    exit 0
fi

echo "=== Toplam süreye göre ilk 10"
"${PSQL[@]}" -x -c "SELECT calls, round(total_exec_time::numeric,1) AS total_ms, round(mean_exec_time::numeric,2) AS mean_ms,
  rows, left(query,200) AS query FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC LIMIT 10"

echo "=== Ortalama süreye göre ilk 10 (en az 20 çağrı)"
"${PSQL[@]}" -x -c "SELECT calls, round(mean_exec_time::numeric,2) AS mean_ms, round(max_exec_time::numeric,1) AS max_ms,
  rows, left(query,200) AS query FROM pg_stat_statements WHERE calls >= 20
  AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_statements%' ORDER BY mean_exec_time DESC LIMIT 10"
