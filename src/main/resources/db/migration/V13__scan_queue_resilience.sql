-- =====================================================================
-- V13: yoğunluğa dayanıklılık (plan Faz 3)
--   scan_jobs.run_after   : geçici hatada (GitHub kesintisi, rate-limit) iş FAILED yerine ertelenir;
--                           bu zamandan önce sahiplenilmez.
--   scan_jobs.deferrals   : erteleme sayacı (üstel geri çekilme + üst sınır).
--   scan_jobs.claim_token : her sahiplenmede yenilenen jeton (fencing). Kalp atışı kesilip devredilen ya da
--                           kapanışta kuyruğa geri bırakılan işin sonucunu geç kalan eski çalıştırma yazamaz.
--   repos.size_kb         : GitHub'ın bildirdiği depo boyutu (KB); kullanıcı kotası (çok büyük depo reddedilir).
--   shedlock              : zamanlanmış işler için dağıtık kilit (2+ instance'ta çift çalışma yok).
--                           Kullanıcı verisi içermez; RLS yok (sistem tablosu).
--
-- Geri alma: DROP TABLE IF EXISTS shedlock;  ALTER TABLE repos DROP COLUMN IF EXISTS size_kb;
--            ALTER TABLE scan_jobs DROP COLUMN IF EXISTS run_after, DROP COLUMN IF EXISTS deferrals,
--                                  DROP COLUMN IF EXISTS claim_token;
--            (önce bu sürümden önceki imaja dönülmeli; eski kod bu kolonları kullanmaz)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

-- Nullable / sabit varsayılanlı kolonlar: tabloyu yeniden yazmaz, kısa kilit
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS run_after   TIMESTAMP;
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS deferrals   INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scan_jobs ADD COLUMN IF NOT EXISTS claim_token UUID;
ALTER TABLE repos     ADD COLUMN IF NOT EXISTS size_kb     INTEGER;

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
