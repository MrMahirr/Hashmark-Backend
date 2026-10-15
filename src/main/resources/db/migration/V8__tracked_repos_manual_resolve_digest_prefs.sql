-- =====================================================================
-- V8: depo takip bayrağı (aday/izlenen), elle çözme, özet e-posta tercihleri
-- =====================================================================

-- Mevcut depolar izleniyor sayılır; yeni senkronlar aday olarak (tracked = FALSE) gelir.
ALTER TABLE repos
    ADD COLUMN IF NOT EXISTS tracked BOOLEAN NOT NULL DEFAULT TRUE;
CREATE INDEX IF NOT EXISTS idx_repos_user_tracked ON repos (user_id) WHERE tracked;

-- Kullanıcının "çözüldü" dediği borç; sonraki tarama kodu hâlâ görürse yeniden açılır.
ALTER TABLE debts
    ADD COLUMN IF NOT EXISTS manually_resolved BOOLEAN NOT NULL DEFAULT FALSE;

-- Haftalık özet: dahil edilecek etiketler (virgülle; NULL = hepsi), alternatif adres, son gönderim günü
ALTER TABLE user_settings
    ADD COLUMN IF NOT EXISTS digest_labels       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS digest_email        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS last_digest_sent_on DATE;

-- Eski varsayılan 'MON' kısaltmasını tam ada çevir
UPDATE user_settings SET notify_day = 'MONDAY' WHERE notify_day = 'MON';
