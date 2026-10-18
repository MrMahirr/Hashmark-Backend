-- =====================================================================
-- V17: "depo başına tek aktif iş" kuralı tür başına (yalnızca indeks; Flyway transaction'sız çalıştırır)
--   SCAN : depo başına en fazla bir PENDING/RUNNING tarama (eski uq_scan_jobs_active_per_repo ile aynı anlam;
--          mevcut satırların hepsi kind = 'SCAN' olduğu için eski indeksi sağlayan veri yenisini de sağlar).
--   PR   : (depo, PR) başına en fazla bir PENDING kontrol. Aynı PR'a yeni push bekleyen işi günceller; çalışan
--          kontrol varken yenisi kuyruğa girebilir (eski commit'in sonucu commit'e özel, yorum yazılmadan önce
--          PR'ın güncel commit'i doğrulanır).
--   Eski indeks, PR işlerinin taramalarla aynı depoda çalışabilmesi için kaldırılır. Yeni kod yalnızca yeni
--   indekslere dayanır; eski kod (V16 öncesi) yeni indekslerle de doğru çalışır (tüm işleri SCAN).
--
-- Geri alma: CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_scan_jobs_active_per_repo ON scan_jobs (repo_id)
--                WHERE status IN ('PENDING', 'RUNNING');   (önce PR işleri silinmeli: kind = 'PR')
--            DROP INDEX CONCURRENTLY IF EXISTS uq_scan_jobs_active_scan_per_repo;
--            DROP INDEX CONCURRENTLY IF EXISTS uq_scan_jobs_pending_pr;
-- lock_timeout Flyway bağlantısında zaten 5 sn (spring.flyway.init-sqls); transaction'sız dosyada SET yazılmaz (V14).
-- =====================================================================

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_scan_jobs_active_scan_per_repo ON scan_jobs (repo_id)
    WHERE status IN ('PENDING', 'RUNNING') AND kind = 'SCAN';

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_scan_jobs_pending_pr ON scan_jobs (repo_id, pr_number)
    WHERE status = 'PENDING' AND kind = 'PR';

DROP INDEX CONCURRENTLY IF EXISTS uq_scan_jobs_active_per_repo;
