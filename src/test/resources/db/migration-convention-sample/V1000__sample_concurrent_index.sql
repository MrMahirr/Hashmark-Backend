-- Yalnızca MigrationConventionsTest için örnek: docs/MIGRATIONS.md'deki "CONCURRENTLY tek dosyada" kalıbı.
-- Geri alma: DROP INDEX CONCURRENTLY IF EXISTS idx_sample_concurrent;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sample_concurrent ON debts (repo_id, label);
