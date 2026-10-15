-- =====================================================================
-- V7: borç kimliği (fingerprint), köken bilgisi (sha/blame), triyaj,
--     parser üst verisi, takip edilen dal, kalıcı iş kuyruğu, tarama anlık görüntüleri
-- =====================================================================

-- ---------- debts ----------
ALTER TABLE debts
    ADD COLUMN IF NOT EXISTS fingerprint     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS occurrence      INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS last_seen_at    TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_seen_sha   VARCHAR(40),
    ADD COLUMN IF NOT EXISTS triage_status   VARCHAR(20)  NOT NULL DEFAULT 'OPEN',
    ADD COLUMN IF NOT EXISTS assigned_to     VARCHAR(100),
    ADD COLUMN IF NOT EXISTS parsed_assignee VARCHAR(100),
    ADD COLUMN IF NOT EXISTS priority        VARCHAR(10),
    ADD COLUMN IF NOT EXISTS due_date        DATE,
    ADD COLUMN IF NOT EXISTS issue_ref       VARCHAR(50),
    ADD COLUMN IF NOT EXISTS author_login    VARCHAR(100),
    ADD COLUMN IF NOT EXISTS author_name     VARCHAR(255),
    ADD COLUMN IF NOT EXISTS introduced_at   TIMESTAMP,
    ADD COLUMN IF NOT EXISTS introduced_sha  VARCHAR(40),
    ADD COLUMN IF NOT EXISTS blame_at        TIMESTAMP;

-- Parmak izi: md5(etiket + ':' + boşlukları tekilleştirilmiş içerik). Uygulama aynı formülü kullanır.
UPDATE debts
SET fingerprint  = md5(label || ':' || btrim(regexp_replace(COALESCE(content, ''), '\s+', ' ', 'g'))),
    last_seen_at = COALESCE(resolved_at, detected_at, NOW())
WHERE fingerprint IS NULL;

-- Aynı dosyada aynı parmak izine sahip kayıtları sırala (açık olanlar önce → 0, 1, ...)
UPDATE debts d
SET occurrence = ranked.rn
FROM (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY repo_id, file_path, fingerprint
               ORDER BY (resolved_at IS NOT NULL), line_no, id
           ) - 1 AS rn
    FROM debts
) ranked
WHERE d.id = ranked.id;

ALTER TABLE debts ALTER COLUMN fingerprint SET NOT NULL;

-- Kimlik artık satır numarası değil: satır kayınca borç aynı kalır
ALTER TABLE debts DROP CONSTRAINT IF EXISTS debts_repo_id_file_path_line_no_key;
CREATE UNIQUE INDEX IF NOT EXISTS uq_debts_identity
    ON debts (repo_id, file_path, fingerprint, occurrence);

CREATE INDEX IF NOT EXISTS idx_debts_repo_open     ON debts (repo_id) WHERE resolved_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_debts_repo_label    ON debts (repo_id, label);
CREATE INDEX IF NOT EXISTS idx_debts_repo_detected ON debts (repo_id, detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_debts_blame_pending ON debts (repo_id)
    WHERE blame_at IS NULL AND resolved_at IS NULL;

-- ---------- repos ----------
ALTER TABLE repos
    ADD COLUMN IF NOT EXISTS tracked_branch   VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_scanned_sha VARCHAR(40);

-- ---------- scan_jobs: kalıcı kuyruk ----------
ALTER TABLE scan_jobs
    ADD COLUMN IF NOT EXISTS user_id      BIGINT,
    ADD COLUMN IF NOT EXISTS branch       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS commit_sha   VARCHAR(40),
    ADD COLUMN IF NOT EXISTS attempts     INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP;

-- Repo başına tek aktif (bekleyen veya çalışan) iş
DROP INDEX IF EXISTS uq_scan_jobs_running_per_repo;
CREATE UNIQUE INDEX IF NOT EXISTS uq_scan_jobs_active_per_repo
    ON scan_jobs (repo_id) WHERE status IN ('PENDING', 'RUNNING');
CREATE INDEX IF NOT EXISTS idx_scan_jobs_pending ON scan_jobs (id) WHERE status = 'PENDING';

-- ---------- scan_snapshots: tarama geçmişi / trend ----------
CREATE TABLE IF NOT EXISTS scan_snapshots (
    id          BIGSERIAL PRIMARY KEY,
    repo_id     BIGINT    NOT NULL REFERENCES repos(id) ON DELETE CASCADE,
    scan_job_id BIGINT    REFERENCES scan_jobs(id) ON DELETE SET NULL,
    taken_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    commit_sha  VARCHAR(40),
    total_open  INTEGER   NOT NULL DEFAULT 0,
    todo_count  INTEGER   NOT NULL DEFAULT 0,
    fixme_count INTEGER   NOT NULL DEFAULT 0,
    hack_count  INTEGER   NOT NULL DEFAULT 0,
    xxx_count   INTEGER   NOT NULL DEFAULT 0,
    note_count  INTEGER   NOT NULL DEFAULT 0,
    doc_count   INTEGER   NOT NULL DEFAULT 0,
    info_count  INTEGER   NOT NULL DEFAULT 0,
    added       INTEGER   NOT NULL DEFAULT 0,
    resolved    INTEGER   NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_scan_snapshots_repo_taken ON scan_snapshots (repo_id, taken_at DESC);
