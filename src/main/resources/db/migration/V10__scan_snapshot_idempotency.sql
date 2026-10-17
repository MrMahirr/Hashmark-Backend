-- =====================================================================
-- V10: tarama sonucu yazımı idempotent olsun (Faz 2 - transaction sınırları)
--   Bir iş yeniden kuyruğa alınıp ikinci kez tamamlanırsa aynı iş için
--   ikinci bir snapshot satırı oluşmaz.
--
-- Geri alma: DROP INDEX IF EXISTS uq_scan_snapshots_job;  (silinen kopyalar geri gelmez; yalnızca
--            aynı scan_job_id'li tekrarlardı, trend en yeni değeri kullandığı için veri kaybı yok)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

-- Aynı işe ait tekrarlar varsa en eskisi kalır
DELETE FROM scan_snapshots s
USING scan_snapshots keep
WHERE s.scan_job_id IS NOT NULL
  AND s.scan_job_id = keep.scan_job_id
  AND s.id > keep.id;

CREATE UNIQUE INDEX IF NOT EXISTS uq_scan_snapshots_job
    ON scan_snapshots (scan_job_id) WHERE scan_job_id IS NOT NULL;
