-- =====================================================================
-- V23: herkese açık depo "debt score" sayfası
--   repos.public_page : sahip açtıysa /p/<badge_token> sayfası not, borç sayıları, etiket/yaş dağılımı ve trendi
--                       gösterir. Rozetten AYRI bir onaydır: rozeti açmış olmak sayfayı açmaz (rozet yalnızca not
--                       gösterir; sayfa daha fazlasını). Anahtar rozetle ortaktır; rozet kapatılınca sayfa da kapanır.
--   Sabit varsayılanlı kolon: tablo yeniden yazılmaz, kilit kısa.
--
-- Geri alma: ALTER TABLE repos DROP COLUMN IF EXISTS public_page;
--            (açık sayfalar 404 döner; rozetler etkilenmez)
-- =====================================================================

SET LOCAL lock_timeout = '5s';

ALTER TABLE repos ADD COLUMN IF NOT EXISTS public_page BOOLEAN NOT NULL DEFAULT FALSE;
