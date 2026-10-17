# Hashmark PostgreSQL: resmi imaj + pgBackRest (PITR, docs/PITR.md).
# Sırlar ve depo ayarları imaja GİRMEZ; çalışma anında PGBACKREST_* ortam değişkenlerinden okunur.
FROM postgres:18-alpine

RUN apk add --no-cache pgbackrest \
    && mkdir -p /var/spool/pgbackrest /var/log/pgbackrest /tmp/pgbackrest \
    && chown postgres:postgres /var/spool/pgbackrest /var/log/pgbackrest /tmp/pgbackrest

COPY --chmod=0755 pgbackrest-archive-push.sh /usr/local/bin/pgbackrest-archive-push.sh
