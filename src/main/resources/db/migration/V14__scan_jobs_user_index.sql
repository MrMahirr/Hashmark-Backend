-- V14: kullanıcı başına tarama kotası (aktif iş sayısı, son 24 saat) ve adil kuyruk sorguları için indeks.
-- lock_timeout Flyway bağlantısında zaten 5 sn (spring.flyway.init-sqls); burada SET yazılmaz: Flyway bu dosyayı
-- transaction'sız çalıştırır ve SET'i transaction'lı ifade sayıp karışık migrasyonu reddeder.
-- Geri alma: DROP INDEX CONCURRENTLY IF EXISTS idx_scan_jobs_user_created;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_scan_jobs_user_created ON scan_jobs (user_id, created_at);
