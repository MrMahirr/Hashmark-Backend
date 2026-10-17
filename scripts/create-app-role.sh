#!/bin/bash
set -euo pipefail

# Row-Level Security için uygulama rolünü oluşturur/günceller (idempotent). docs/ROW_LEVEL_SECURITY.md
# Rol: giriş yapabilir, süper kullanıcı DEĞİL, RLS'i atlayamaz, tablo sahibi DEĞİL; yalnızca DML yetkisi.
# Migrasyonlar sahip rolle (POSTGRES_USER) çalışmaya devam eder; yeni tablolar varsayılan yetkiyle gelir.
#
# Kullanım:  DB_APP_PASSWORD='<güçlü parola>' ./scripts/create-app-role.sh
#            (ardından .env'de DB_APP_USER / DB_APP_PASSWORD ayarlanır ve backend yeniden başlatılır)
CONTAINER="${DB_CONTAINER:-hashmark_postgres}"
OWNER="${POSTGRES_USER:-hashmark}"
DB_NAME="${POSTGRES_DB:-hashmark}"
APP_USER="${DB_APP_USER:-hashmark_app}"
: "${DB_APP_PASSWORD:?DB_APP_PASSWORD gerekli (openssl rand -base64 24)}"

if [ "$APP_USER" = "$OWNER" ]; then
    echo "✗ DB_APP_USER sahip rolle ($OWNER) aynı olamaz" >&2
    exit 1
fi

docker exec -i "$CONTAINER" psql -U "$OWNER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q \
    -v app_user="$APP_USER" -v app_password="$DB_APP_PASSWORD" -v owner="$OWNER" -v db="$DB_NAME" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'app_user')
 WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'app_user') \gexec
-- Parola ve kısıtlar her çalıştırmada yeniden yazılır (parola dönüşü için de kullanılır)
SELECT format('ALTER ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION CONNECTION LIMIT 40',
              :'app_user', :'app_password') \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', :'db', :'app_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'app_user') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'app_user') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'app_user') \gexec
-- Sonraki migrasyonların (sahip rolün) oluşturacağı tablolar için
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
              :'owner', :'app_user') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I',
              :'owner', :'app_user') \gexec
-- Migrasyon geçmişi uygulamanın işi değil
SELECT format('REVOKE ALL ON TABLE flyway_schema_history FROM %I', :'app_user')
 WHERE to_regclass('flyway_schema_history') IS NOT NULL \gexec

-- Doğrulama: rol RLS'e tabi mi?
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = :'app_user';
SQL
echo "✓ $APP_USER hazır. .env: DB_APP_USER=$APP_USER ve DB_APP_PASSWORD=... → docker compose up -d backend"
