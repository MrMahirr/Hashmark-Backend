-- =====================================================================
-- V20: analiz ve içgörü
--   debts.priority_auto       : öncelik koddan yazılmadı, anahtar kelimeden (security, crash...) çıkarıldı
--   debts.secret_detected     : yorumda sır (token, parola) bulundu ve maskelendi; her taramada yeniden yazılır
--   user_settings.hourly_rate : teknik borç maliyeti için saatlik ücret (NULL = ayarlanmadı)
--   user_settings.currency    : ücretin para birimi (ISO 4217, ör. TRY, USD)
--   Sabit varsayılanlı / nullable kolonlar: PostgreSQL 11+ tabloyu yeniden yazmaz, kilit kısa.
--
-- Geri alma: ALTER TABLE debts DROP COLUMN IF EXISTS priority_auto, DROP COLUMN IF EXISTS secret_detected;
--            ALTER TABLE user_settings DROP COLUMN IF EXISTS hourly_rate, DROP COLUMN IF EXISTS currency;
--            (çıkarılmış öncelikler debts.priority'de kalır; sonraki tarama onları yeniden yazar)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE debts ADD COLUMN IF NOT EXISTS priority_auto   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE debts ADD COLUMN IF NOT EXISTS secret_detected BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS hourly_rate NUMERIC(10, 2);
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS currency    VARCHAR(3);
