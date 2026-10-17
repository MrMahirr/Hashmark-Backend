-- =====================================================================
-- V12: kullanıcı onayı (plan 5.10, K7)
--   user_consents: hangi politika sürümüne, hangi metne (SHA-256) ne zaman onay verildi / geri çekildi.
--   IP ve User-Agent bilinçli olarak SAKLANMAZ (veri minimizasyonu).
--   repos.private_ack_at: özel depo, kullanıcı ek uyarıyı onaylamadan taranmaz.
--
-- Geri alma: DROP TABLE IF EXISTS user_consents;  ALTER TABLE repos DROP COLUMN IF EXISTS private_ack_at;
--            (onay kayıtları kaybolur; kapı açıksa kullanıcılardan yeniden onay istenir)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS user_consents (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type            VARCHAR(32) NOT NULL,
    policy_version  INTEGER     NOT NULL,
    language        VARCHAR(5)  NOT NULL,
    text_hash       VARCHAR(64) NOT NULL,
    accepted_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    withdrawn_at    TIMESTAMP
);
-- Kullanıcı ve tür başına en fazla bir etkin onay
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_consents_active
    ON user_consents (user_id, type) WHERE withdrawn_at IS NULL;

-- Sabit varsayılansız nullable kolon: tabloyu yeniden yazmaz, kısa kilit
ALTER TABLE repos ADD COLUMN IF NOT EXISTS private_ack_at TIMESTAMP;

-- RLS (V11 ile aynı model)
ALTER TABLE user_consents ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS user_consents_tenant ON user_consents;
CREATE POLICY user_consents_tenant ON user_consents
    USING (app_rls_bypass() OR user_id = app_current_user_id())
    WITH CHECK (app_rls_bypass() OR user_id = app_current_user_id());
