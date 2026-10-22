-- =====================================================================
-- V21: raporlama ve bildirim
--   user_settings.digest_frequency : DAILY | WEEKLY | MONTHLY (haftalık: notify_day; aylık: ayın 1'i)
--   user_settings.digest_hour      : gönderim saati (0–23), kullanıcının saat diliminde
--   user_settings.digest_timezone  : IANA adı (ör. Europe/Istanbul). Varsayılanlar eski davranışla aynı:
--                                    haftalık, 08:00 İstanbul
--   user_settings.alert_max_open   : açık borç bu sayıyı AŞINCA kanallara uyarı (NULL = kapalı)
--   user_settings.alert_on_secret  : taramada yeni sızan sır bulununca kanallara uyarı
--   notification_channels          : Slack / Discord / Teams gelen webhook adresleri. Adres bir sırdır:
--                                    AES-GCM ile şifreli saklanır (users.github_token ile aynı), arayüzde
--                                    yalnızca maskeli ipucu (url_hint) gösterilir. RLS: V11 modeli.
--   repos.badge_token              : README rozeti için tahmin edilemez anahtar (NULL = rozet kapalı).
--                                    Benzersiz indeks V22'de (CONCURRENTLY, ayrı dosya).
--   Sabit varsayılanlı / nullable kolonlar ve yeni tablo: tablo yeniden yazılmaz, kilit kısa.
--
-- Geri alma: DROP TABLE IF EXISTS notification_channels;
--            ALTER TABLE user_settings DROP COLUMN IF EXISTS digest_frequency, DROP COLUMN IF EXISTS digest_hour,
--                DROP COLUMN IF EXISTS digest_timezone, DROP COLUMN IF EXISTS alert_max_open,
--                DROP COLUMN IF EXISTS alert_on_secret;
--            ALTER TABLE repos DROP COLUMN IF EXISTS badge_token;   (önce V22 geri alınmalı)
--            (kanal adresleri ve rozet bağlantıları kaybolur; README'lerdeki rozetler 404 alır)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS digest_frequency VARCHAR(8)  NOT NULL DEFAULT 'WEEKLY';
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS digest_hour      SMALLINT    NOT NULL DEFAULT 8;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS digest_timezone  VARCHAR(64) NOT NULL DEFAULT 'Europe/Istanbul';
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS alert_max_open   INTEGER;
ALTER TABLE user_settings ADD COLUMN IF NOT EXISTS alert_on_secret  BOOLEAN     NOT NULL DEFAULT TRUE;

CREATE TABLE IF NOT EXISTS notification_channels (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    kind           VARCHAR(8)   NOT NULL,
    url_encrypted  TEXT         NOT NULL,
    url_hint       VARCHAR(80)  NOT NULL,
    send_digest    BOOLEAN      NOT NULL DEFAULT TRUE,
    send_alerts    BOOLEAN      NOT NULL DEFAULT TRUE,
    last_status    VARCHAR(8),
    last_error     VARCHAR(200),
    last_sent_at   TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_notification_channels_user ON notification_channels (user_id);

ALTER TABLE notification_channels ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS notification_channels_tenant ON notification_channels;
CREATE POLICY notification_channels_tenant ON notification_channels
    USING (app_rls_bypass() OR user_id = app_current_user_id())
    WITH CHECK (app_rls_bypass() OR user_id = app_current_user_id());

ALTER TABLE repos ADD COLUMN IF NOT EXISTS badge_token VARCHAR(43);
