-- =====================================================================
-- V16: PR borç kontrolü ("debt budget") + webhook teslimat tekilleştirme
--   scan_jobs.kind            : SCAN (depo taraması) | PR (pull request kontrolü). Aynı kalıcı kuyruk, fencing ve
--                               erteleme her iki tür için ortaktır.
--   scan_jobs.pr_number       : PR numarası (yalnızca kind = PR)
--   scan_jobs.pr_head_sha     : kontrol edilen commit; aynı PR'a yeni push gelirse bekleyen işte güncellenir
--   scan_jobs.pr_base_sha     : karşılaştırma tabanı (compare base...head)
--   repos.webhook_version     : hook'un abone olduğu olay kümesinin sürümü. 1 = yalnızca push (V15),
--                               2 = push + pull_request + issues. Eski hook'lar arka plan işiyle yükseltilir.
--   webhook_deliveries        : GitHub'ın X-GitHub-Delivery kimliği; aynı teslimat (yeniden gönderim) iki kez
--                               işlenmez. Kullanıcı verisi içermez (yalnızca rastgele kimlik + zaman); RLS yok.
--                               7 günden eskiler saklama işiyle silinir.
--   Hepsi nullable / sabit varsayılanlı kolon ya da yeni tablo: tablo yeniden yazılmaz, kilit kısa.
--   Tür başına "depo başına tek aktif iş" indeksi V17'dedir (CONCURRENTLY, ayrı dosya).
--
-- Geri alma: DROP TABLE IF EXISTS webhook_deliveries;
--            ALTER TABLE repos DROP COLUMN IF EXISTS webhook_version;
--            ALTER TABLE scan_jobs DROP COLUMN IF EXISTS kind, DROP COLUMN IF EXISTS pr_number,
--                DROP COLUMN IF EXISTS pr_head_sha, DROP COLUMN IF EXISTS pr_base_sha;
--            (önce V17 geri alınmalı; PR işleri kuyrukta kalmamalı: DELETE FROM scan_jobs WHERE kind = 'PR')
-- =====================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS kind        VARCHAR(8) NOT NULL DEFAULT 'SCAN';
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS pr_number   INTEGER;
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS pr_head_sha VARCHAR(40);
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS pr_base_sha VARCHAR(40);

ALTER TABLE repos ADD COLUMN IF NOT EXISTS webhook_version INTEGER NOT NULL DEFAULT 1;

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    delivery_id VARCHAR(64) PRIMARY KEY,
    received_at TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_webhook_deliveries_received ON webhook_deliveries (received_at);
