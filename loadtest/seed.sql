-- Yük testi tohum verisi: 200 kullanıcı x 3 takip edilen depo x 500 borç = 300.000 borç satırı.
-- Yalnızca TEST veritabanında çalıştırın (kullanıcı kimlikleri 1..200 ve github_id 'lt-N' kullanılır).
--   docker exec -i <pg> psql -U <user> -d <db> -v ON_ERROR_STOP=1 < loadtest/seed.sql

INSERT INTO users (id, github_id, github_login, name, email, github_token)
SELECT n, 'lt-' || n, 'seed-' || n, 'Seed ' || n, 'seed-' || n || '@example.invalid', NULL
FROM generate_series(1, 200) n
ON CONFLICT DO NOTHING;

INSERT INTO repos (id, user_id, github_repo_id, full_name, private, tracked, tracked_branch)
SELECT (u - 1) * 3 + r, u, 'lt-repo-' || u || '-' || r, 'seed-' || u || '/repo-' || r, (r = 1), TRUE, 'main'
FROM generate_series(1, 200) u, generate_series(1, 3) r
ON CONFLICT DO NOTHING;

INSERT INTO debts (repo_id, file_path, line_no, label, content, fingerprint, occurrence,
                   triage_status, priority, detected_at, last_seen_at)
SELECT rp.id,
       'src/module' || (g % 40) || '/File' || (g % 97) || '.java',
       g,
       (ARRAY['TODO','FIXME','HACK','XXX','NOTE'])[1 + g % 5],
       'seed comment ' || g || ' for repo ' || rp.id,
       md5(rp.id || ':' || g),
       0,
       (ARRAY['OPEN','OPEN','OPEN','RESOLVED'])[1 + g % 4],
       (ARRAY['LOW','MEDIUM','HIGH', NULL])[1 + g % 4],
       NOW() - (g % 365 || ' days')::interval,
       NOW()
FROM repos rp, generate_series(1, 500) g
WHERE rp.user_id <= 200 AND rp.full_name LIKE 'seed-%'
ON CONFLICT DO NOTHING;

SELECT setval('users_id_seq', GREATEST((SELECT MAX(id) FROM users), 1));
SELECT setval('repos_id_seq', GREATEST((SELECT MAX(id) FROM repos), 1));
ANALYZE;
