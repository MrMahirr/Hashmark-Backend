-- =====================================================================
-- V18: borçtan GitHub Issue oluşturma
--   debts.github_issue_number : Hashmark'ın açtığı issue (aynı borç için ikinci kez açılmaz)
--   debts.github_issue_url    : arayüzde bağlantı
--   debts.github_issue_state  : open | closed (issues webhook'u ile güncellenir; kapanınca borç elle çözülmüş sayılır)
--   Nullable kolonlar: tablo yeniden yazılmaz, kilit kısa. Webhook araması için indeks V19'da (CONCURRENTLY).
--   Not: debts.issue_ref (yorumdaki "#12" gibi referans) ayrıdır ve parser'a aittir.
--
-- Geri alma: ALTER TABLE debts DROP COLUMN IF EXISTS github_issue_number, DROP COLUMN IF EXISTS github_issue_url,
--                DROP COLUMN IF EXISTS github_issue_state;
--            (GitHub'daki issue'lar kalır; yalnızca bağlantı kaybolur. Önce V19 geri alınmalı.)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE debts ADD COLUMN IF NOT EXISTS github_issue_number INTEGER;
ALTER TABLE debts ADD COLUMN IF NOT EXISTS github_issue_url    TEXT;
ALTER TABLE debts ADD COLUMN IF NOT EXISTS github_issue_state  VARCHAR(8);
