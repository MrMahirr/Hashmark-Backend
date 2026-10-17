# PostgreSQL ince ayar ve otomatik vakum izleme

Hedef: ~2–4 GB RAM'li tek sunucu (backend 1 GB, Postgres ~1 GB, kalan işletim sistemi + önbellek).
Değerler **başlangıç** değerleridir; ölçüm (Faz 1 yük testi, `pg_stat_statements`) değiştirmeyi gerektirirse güncellenir.

## 1. Sunucu ayarları (`docker-compose.yml` → `postgres.command`)

| Ayar | Değer | Ortam değişkeni | Gerekçe |
|---|---|---|---|
| `shared_buffers` | 256MB | `PG_SHARED_BUFFERS` | Postgres'e ayrılan RAM'in ~%25'i. Bugünkü veri (<100 MB) tamamen sığar. |
| `effective_cache_size` | 768MB | `PG_EFFECTIVE_CACHE_SIZE` | Yalnızca planlayıcı ipucu (bellek ayırmaz): OS önbelleği + shared_buffers. |
| `work_mem` | 8MB | `PG_WORK_MEM` | Sıralama/hash başına. En kötü durum ≈ bağlantı (15) × düğüm × 8 MB; export ve rapor sıralamaları diske taşmaz. |
| `maintenance_work_mem` | 64MB | `PG_MAINTENANCE_WORK_MEM` | Vakum ve `CREATE INDEX` hızı. |
| `max_connections` | 50 | `PG_MAX_CONNECTIONS` | Hikari 15 + Flyway 1 + yedek/izleme/psql. Varsayılan 100 boşuna bellek ayırır. 2. instance gelirse (Faz 4) 15×2 + pay → yeniden hesaplanır ya da PgBouncer. |
| `idle_in_transaction_session_timeout` | 5min | `PG_IDLE_IN_TX_TIMEOUT` | Sunucu güvenlik ağı (psql'de açık unutulan `BEGIN` dahil). Uygulama oturumu kendi 60 sn'sini ayarlar. |
| `random_page_cost` | 1.1 | — | SSD; indeks taramasını doğru fiyatlar. |
| `log_autovacuum_min_duration` | 10s | — | Uzun süren vakumlar logda görünür. |
| `track_io_timing` | on | — | `EXPLAIN (ANALYZE, BUFFERS)` ve `pg_stat_statements` I/O süresini gösterir. |
| `autovacuum_vacuum_scale_factor` / `analyze_scale_factor` | 0.05 | — | Varsayılan 0.2: 100 bin satırlık `debts`'te vakum ancak 20 bin ölü satırda başlar. Tarama her seferinde çözülen satırları günceller; 0.05 daha erken temizler. |
| `shm_size` (compose) | 256mb | — | Paralel sorgular `/dev/shm` kullanır; Docker varsayılanı 64 MB. |

Değişiklik uygulamak: `.env`'e değişkeni yaz → `docker compose up -d postgres` (yeniden başlatma, birkaç sn kesinti).
Doğrulama:
```bash
docker exec hashmark_postgres psql -U hashmark -d hashmark -c \
  "SELECT name, setting, unit FROM pg_settings WHERE name IN ('shared_buffers','work_mem','max_connections','idle_in_transaction_session_timeout')"
```

**Büyütme kuralı:** sunucu RAM'i artarsa `shared_buffers` ≈ Postgres payının %25'i, `effective_cache_size` ≈ %50–75'i.
`work_mem`'i yükseltmeden önce `pg_stat_statements`'ta `temp_blks_written > 0` olan sorgu var mı bakın.

## 2. Oturum sınırları (uygulama, `application.yml` → Hikari `connection-init-sql`)

| Ayar | Varsayılan | Ortam değişkeni | Aşılınca |
|---|---|---|---|
| `statement_timeout` | 30s | `DB_STATEMENT_TIMEOUT` | Sorgu iptal (57014) → API **503 + Retry-After**, `hashmark_db_timeout_total{kind="statement"}` |
| `lock_timeout` | 10s | `DB_LOCK_TIMEOUT` | Kilit alınamadı (55P03) → 503, `kind="lock"` |
| `idle_in_transaction_session_timeout` | 60s | `DB_IDLE_IN_TX_TIMEOUT` | Bağlantı kapatılır; Hikari yenisini açar |

Neden 30 sn: en uzun meşru ifadeler tarama sonucu yazımı (≤20.000 satır), export ve saklama işi partileridir
(5.000 satır). Bunların süresi büyük veride **ölçülmedi**; 30 sn bilinçli olarak geniş tutuldu ve yalnızca
kaçak sorguyu kesmeyi amaçlar. 1 sn'yi aşan ifadeler Postgres loguna düşer (`log_min_duration_statement`);
üretimde 10 sn'ye yaklaşan bir ifade görülürse sınırı düşürmeden önce sorgu düzeltilir. Flyway bu sınırları **taşımaz**
(ayrı bağlantı, `statement_timeout=0`, `lock_timeout=5s`; bkz. `docs/MIGRATIONS.md`).
Davranış `DatabaseSessionSettingsTest` ile gerçek PG'de doğrulanır.

> Not: Spring 7, PostgreSQL'in `55P03` kodunu sınıflandırmaz (`UncategorizedSQLException`);
> `GlobalExceptionHandler` bu SQLSTATE'i ayrıca yakalar.

## 3. Otomatik vakum izleme

Backend, `pg_stat_user_tables`'tan şu göstergeleri yayınlar (60 sn önbellekli; ayrı exporter yok):

| Metrik | Anlam |
|---|---|
| `hashmark_db_table_live_rows{table}` | Canlı satır (tahmini) |
| `hashmark_db_table_dead_rows{table}` | Vakum bekleyen ölü satır |
| `hashmark_db_table_size_bytes{table}` | Tablo + indeks + TOAST |
| `hashmark_db_table_vacuum_age_seconds{table}` | Son (oto)vakumdan beri süre |

Alarmlar (`monitoring/alerts.yml`): **DbAutovacuumLagging** (ölü satır >10 bin ve oran >%20, 6 saat),
**DbTimeouts** (10 dk'da >5 zaman aşımı). Disk büyümesi için mevcut **DiskUsageHigh** ve `size_bytes` trendi.

### Vakum geride (runbook)
1. **Belirti:** DbAutovacuumLagging ya da tablo boyutu canlı satırla orantısız büyüyor.
2. **Teşhis:**
   ```sql
   -- Vakumu engelleyen en eski transaction
   SELECT pid, state, now() - xact_start AS age, left(query, 80)
   FROM pg_stat_activity WHERE xact_start IS NOT NULL ORDER BY xact_start LIMIT 5;
   -- Tablo durumu
   SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, autovacuum_count
   FROM pg_stat_user_tables ORDER BY n_dead_tup DESC LIMIT 10;
   ```
3. **Müdahale:** uzun açık oturumu sonlandır (`SELECT pg_terminate_backend(<pid>)`), sonra `VACUUM (VERBOSE, ANALYZE) <tablo>;`
   (çevrimiçi, kilitlemez). `VACUUM FULL` **kullanmayın** (tabloyu kilitler).
4. **Doğrulama:** `n_dead_tup` düşer, alarm 6 saat içinde kapanır.

## 4. Yavaş sorgular
`./scripts/pg-top-queries.sh` (en pahalı/en yavaş 10 sorgu) → `EXPLAIN (ANALYZE, BUFFERS)`.
1 sn'den uzun her ifade Postgres logunda (`log_min_duration_statement=1000`).
