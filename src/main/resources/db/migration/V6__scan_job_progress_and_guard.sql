-- Tarama ilerleme / sonuç bilgileri
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS files_scanned INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS files_skipped INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS files_failed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS message TEXT;

-- Takılı kalmış eski RUNNING kayıtları, aşağıdaki benzersiz indeksi ihlal etmesin
UPDATE scan_jobs SET status = 'FAILED', finished_at = NOW(), message = 'Interrupted'
WHERE status = 'RUNNING';

-- Bir repo için aynı anda yalnızca bir tarama çalışabilir
CREATE UNIQUE INDEX IF NOT EXISTS uq_scan_jobs_running_per_repo
    ON scan_jobs (repo_id) WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS idx_scan_jobs_repo_created ON scan_jobs (repo_id, created_at DESC);
