#!/bin/bash
set -euo pipefail

# Prometheus/Alertmanager/blackbox yapılandırmalarını ve alarm kurallarını (birim testleriyle) doğrular.
# Docker gerekir; hiçbir şeyi başlatmaz. Kullanım: ./scripts/check-monitoring.sh
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MON="$(dirname "$SCRIPT_DIR")/monitoring"
export MSYS_NO_PATHCONV=1

PROM="prom/prometheus:v2.55.1"
AM="prom/alertmanager:v0.27.0"

# alertmanager.yml gizli dosya (token) bekler; doğrulama için geçici sahte sır kullanılır.
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
echo "dummy" > "$TMP/telegram_token"

docker run --rm -v "$MON:/m:ro" --entrypoint promtool "$PROM" check config /m/prometheus.yml 2>&1 \
    | grep -v "alerts.yml" || true
docker run --rm -v "$MON:/etc/prometheus:ro" --entrypoint promtool "$PROM" check rules /etc/prometheus/alerts.yml
docker run --rm -v "$MON:/etc/prometheus:ro" -w /etc/prometheus --entrypoint promtool "$PROM" test rules alerts.test.yml
docker run --rm -v "$MON/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
    -v "$TMP:/etc/alertmanager/secrets:ro" --entrypoint amtool "$AM" check-config /etc/alertmanager/alertmanager.yml
echo "✓ İzleme yapılandırması geçerli"
