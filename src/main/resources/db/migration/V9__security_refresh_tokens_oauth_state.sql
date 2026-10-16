-- =====================================================================
-- V9: kalıcı refresh token (rotation + reuse detection) ve tek kullanımlık OAuth state
-- =====================================================================

-- Her refresh token bir satırdır (jti). Aynı oturumdan türeyen tokenlar aynı family_id'yi taşır.
-- Kullanılmış (used_at) bir token tekrar sunulursa aile çalınmış sayılır ve tamamen iptal edilir.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    jti         VARCHAR(64) PRIMARY KEY,
    family_id   VARCHAR(64) NOT NULL,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at  TIMESTAMP   NOT NULL,
    used_at     TIMESTAMP,
    revoked_at  TIMESTAMP,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_family  ON refresh_tokens (family_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user    ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- OAuth "state": /auth/github üretir, /auth/callback bir kez tüketir (login CSRF koruması).
CREATE TABLE IF NOT EXISTS oauth_states (
    state       VARCHAR(64) PRIMARY KEY,
    expires_at  TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_oauth_states_expires ON oauth_states (expires_at);
