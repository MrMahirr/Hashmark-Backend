-- =====================================================================
-- V11: Row-Level Security (plan 5.5, K4 — kullanıcı verisi karışmaz)
--   Uygulama her bağlantıda iki oturum ayarı yazar (TenantAwareDataSource):
--     app.user_id    : istekteki kimlik (JWT) ya da işlenen işin sahibi
--     app.bypass_rls : 'on' yalnızca kullanıcılar arası sistem işlerinde (kuyruk, saklama, rapor, metrik)
--   İkisi de yoksa politika HİÇBİR satır göstermez (kapalı başarısızlık).
--
--   ENABLE (FORCE değil): tablo sahibi (migrasyon kullanıcısı) ve süper kullanıcı politikayı atlar; bu yüzden
--   bu migrasyon tek başına davranışı DEĞİŞTİRMEZ. Koruma, uygulama ayrı ve tablo sahibi olmayan bir rolle
--   (DATABASE_USERNAME=hashmark_app) bağlandığında devreye girer: docs/ROW_LEVEL_SECURITY.md.
--
-- Geri alma: her tablo için  ALTER TABLE <t> DISABLE ROW LEVEL SECURITY;  DROP POLICY IF EXISTS <t>_tenant ON <t>;
--            sonra  DROP FUNCTION IF EXISTS app_current_user_id(), app_rls_bypass();
--            (Veri değişmez; yalnızca erişim kuralı kalkar.)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

CREATE OR REPLACE FUNCTION app_current_user_id() RETURNS BIGINT
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT NULLIF(current_setting('app.user_id', true), '')::BIGINT $$;

CREATE OR REPLACE FUNCTION app_rls_bypass() RETURNS BOOLEAN
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$ SELECT COALESCE(current_setting('app.bypass_rls', true), '') = 'on' $$;

-- Doğrudan kullanıcıya bağlı tablolar
ALTER TABLE repos ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS repos_tenant ON repos;
CREATE POLICY repos_tenant ON repos
    USING (app_rls_bypass() OR user_id = app_current_user_id())
    WITH CHECK (app_rls_bypass() OR user_id = app_current_user_id());

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS notifications_tenant ON notifications;
CREATE POLICY notifications_tenant ON notifications
    USING (app_rls_bypass() OR user_id = app_current_user_id())
    WITH CHECK (app_rls_bypass() OR user_id = app_current_user_id());

ALTER TABLE user_settings ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS user_settings_tenant ON user_settings;
CREATE POLICY user_settings_tenant ON user_settings
    USING (app_rls_bypass() OR user_id = app_current_user_id())
    WITH CHECK (app_rls_bypass() OR user_id = app_current_user_id());

-- Depo üzerinden kullanıcıya bağlı tablolar (alt sorgu repos'un kendi politikasından da geçer;
-- repos(user_id, github_repo_id) benzersiz indeksi user_id aramasını karşılar)
ALTER TABLE debts ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS debts_tenant ON debts;
CREATE POLICY debts_tenant ON debts
    USING (app_rls_bypass() OR repo_id IN (SELECT id FROM repos WHERE user_id = app_current_user_id()))
    WITH CHECK (app_rls_bypass() OR repo_id IN (SELECT id FROM repos WHERE user_id = app_current_user_id()));

ALTER TABLE scan_jobs ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS scan_jobs_tenant ON scan_jobs;
CREATE POLICY scan_jobs_tenant ON scan_jobs
    USING (app_rls_bypass() OR repo_id IN (SELECT id FROM repos WHERE user_id = app_current_user_id()))
    WITH CHECK (app_rls_bypass() OR repo_id IN (SELECT id FROM repos WHERE user_id = app_current_user_id()));

ALTER TABLE scan_snapshots ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS scan_snapshots_tenant ON scan_snapshots;
CREATE POLICY scan_snapshots_tenant ON scan_snapshots
    USING (app_rls_bypass() OR repo_id IN (SELECT id FROM repos WHERE user_id = app_current_user_id()))
    WITH CHECK (app_rls_bypass() OR repo_id IN (SELECT id FROM repos WHERE user_id = app_current_user_id()));
