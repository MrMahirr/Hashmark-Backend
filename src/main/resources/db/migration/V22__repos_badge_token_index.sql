-- V22: README rozeti isteğinde depoyu anahtarla bulmak için benzersiz kısmi indeks (yalnızca indeks;
-- Flyway transaction'sız çalıştırır, SET yazılmaz — bkz. V14).
-- Geri alma: DROP INDEX CONCURRENTLY IF EXISTS uq_repos_badge_token;
CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uq_repos_badge_token ON repos (badge_token)
    WHERE badge_token IS NOT NULL;
