#!/bin/sh
# PostgreSQL archive_command (docs/PITR.md). Depo yapılandırılmamışsa WAL kabul edilip atılır:
# aksi hâlde başarısız arşiv WAL'ı pg_wal'da biriktirip diski doldurur. Yapılandırılmışsa pgBackRest'e verilir.
if [ -z "${PGBACKREST_REPO1_TYPE:-}" ]; then
    exit 0
fi
exec pgbackrest archive-push "$1"
