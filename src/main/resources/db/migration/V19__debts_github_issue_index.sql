-- V19: issues webhook'unun borcu (depo + issue numarası) ile bulması için kısmi indeks (yalnızca indeks;
-- Flyway transaction'sız çalıştırır, SET yazılmaz — bkz. V14).
-- Geri alma: DROP INDEX CONCURRENTLY IF EXISTS idx_debts_repo_github_issue;
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_debts_repo_github_issue ON debts (repo_id, github_issue_number)
    WHERE github_issue_number IS NOT NULL;
