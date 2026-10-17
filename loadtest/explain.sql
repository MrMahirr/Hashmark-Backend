-- En sık/en pahalı dashboard sorgularının planları (kullanıcı 1; 3 depo x 500 borç; toplamda 300.000 satır).
\echo === 1. Borç listesi (varsayılan sıralama, ilk sayfa)
EXPLAIN (ANALYZE, BUFFERS)
SELECT d.*, r.full_name AS repo_full_name FROM debts d JOIN repos r ON d.repo_id = r.id
WHERE r.user_id = 1 ORDER BY d.detected_at DESC NULLS LAST, d.id DESC LIMIT 50 OFFSET 0;

\echo === 2. Etikete göre açık borç sayımı (stats)
EXPLAIN (ANALYZE, BUFFERS)
SELECT d.repo_id, d.label, COUNT(*) AS n FROM debts d JOIN repos r ON r.id = d.repo_id
WHERE r.user_id = 1 AND r.tracked AND d.resolved_at IS NULL AND d.triage_status = 'OPEN' GROUP BY d.repo_id, d.label;

\echo === 3. Modül dağılımı (report/summary)
EXPLAIN (ANALYZE, BUFFERS)
SELECT split_part(file_path, '/', 2) AS module, COUNT(*) AS cnt FROM debts d JOIN repos r ON d.repo_id = r.id
WHERE r.user_id = 1 AND d.resolved_at IS NULL AND d.triage_status = 'OPEN' GROUP BY module ORDER BY cnt DESC LIMIT 10;

\echo === 4. Filtreli liste (etiket + açık)
EXPLAIN (ANALYZE, BUFFERS)
SELECT d.*, r.full_name FROM debts d JOIN repos r ON d.repo_id = r.id
WHERE r.user_id = 1 AND d.label = 'TODO' AND d.resolved_at IS NULL ORDER BY d.detected_at DESC LIMIT 50;

\echo === 5. Depo listesi + borç sayısı (alt sorgu)
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.*, (SELECT COUNT(*) FROM debts d WHERE d.repo_id = r.id AND d.resolved_at IS NULL AND d.triage_status = 'OPEN') AS debt_count
FROM repos r WHERE r.user_id = 1 AND r.tracked ORDER BY r.created_at DESC;

\echo === Mevcut indeksler (debts / repos)
SELECT tablename, indexname, indexdef FROM pg_indexes WHERE tablename IN ('debts','repos') ORDER BY 1,2;
