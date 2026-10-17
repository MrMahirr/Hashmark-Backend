-- =====================================================================
-- V15: push webhook ile otomatik tarama (V13/V14 Faz 3'ün kuyruk dayanıklılığı migrasyonlarıdır)
--   repos.webhook_id        : GitHub'daki hook kimliği (NULL = otomatik tarama kapalı)
--   repos.webhook_secret    : HMAC imza anahtarı, AES-GCM ile şifreli (users.github_token ile aynı)
--   repos.rescan_requested  : tarama SÜRERKEN gelen push; iş bitince bir tarama daha kuyruğa alınır
--   scan_jobs.triggered_by  : MANUAL | PUSH
--   Hepsi nullable ya da sabit varsayılanlı kolon: tablo yeniden yazılmaz, kilit kısa.
--
-- Geri alma: ALTER TABLE repos DROP COLUMN IF EXISTS webhook_id, DROP COLUMN IF EXISTS webhook_secret,
--                DROP COLUMN IF EXISTS webhook_created_at, DROP COLUMN IF EXISTS rescan_requested;
--            ALTER TABLE scan_jobs DROP COLUMN IF EXISTS triggered_by;
--            (GitHub'daki hook'lar kalır; teslimatlar 404 alır. Önce arayüzden otomatik taramayı kapatın.)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE repos ADD COLUMN IF NOT EXISTS webhook_id BIGINT;
ALTER TABLE repos ADD COLUMN IF NOT EXISTS webhook_secret TEXT;
ALTER TABLE repos ADD COLUMN IF NOT EXISTS webhook_created_at TIMESTAMP;
ALTER TABLE repos ADD COLUMN IF NOT EXISTS rescan_requested BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS triggered_by VARCHAR(16) NOT NULL DEFAULT 'MANUAL';
