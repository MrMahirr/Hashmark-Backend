package dev.hashmark.scanner.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;

/** Her tamamlanan taramanın özet fotoğrafı; trend grafiği bunlardan üretilir. */
@Repository
public class ScanSnapshotRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScanSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Aynı iş için ikinci çağrı sessizce yok sayılır (idempotent). */
    public void save(Long repoId, Long scanJobId, String commitSha, int totalOpen,
                     Map<String, Integer> labelCounts, int added, int resolved) {
        String sql = "INSERT INTO scan_snapshots (repo_id, scan_job_id, commit_sha, total_open, " +
                     "todo_count, fixme_count, hack_count, xxx_count, note_count, doc_count, info_count, " +
                     "added, resolved) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (scan_job_id) WHERE scan_job_id IS NOT NULL DO NOTHING";
        jdbcTemplate.update(sql, repoId, scanJobId, commitSha, totalOpen,
                labelCounts.getOrDefault("TODO", 0),
                labelCounts.getOrDefault("FIXME", 0),
                labelCounts.getOrDefault("HACK", 0),
                labelCounts.getOrDefault("XXX", 0),
                labelCounts.getOrDefault("NOTE", 0),
                labelCounts.getOrDefault("DOC", 0),
                labelCounts.getOrDefault("INFO", 0),
                added, resolved);
    }

    /**
     * {@code days} günden eski snapshot'ları depo + ISO haftası başına tek satıra indirger: haftanın son
     * satırı kalır (toplam ve etiket sayıları ondan), {@code added}/{@code resolved} haftanın toplamı olur.
     * Trend haftalık hesaplandığı için sonuç değişmez. Tekrar çalıştırmak güvenlidir.
     *
     * @return silinen satır sayısı
     */
    public int compactToWeekly(int days) {
        String sql = "WITH grp AS (" +
                     "  SELECT id," +
                     "    ROW_NUMBER() OVER w AS rn," +
                     "    SUM(added) OVER (PARTITION BY repo_id, date_trunc('week', taken_at)) AS sum_added," +
                     "    SUM(resolved) OVER (PARTITION BY repo_id, date_trunc('week', taken_at)) AS sum_resolved," +
                     "    COUNT(*) OVER (PARTITION BY repo_id, date_trunc('week', taken_at)) AS cnt" +
                     "  FROM scan_snapshots WHERE taken_at < NOW() - (? * INTERVAL '1 day')" +
                     "  WINDOW w AS (PARTITION BY repo_id, date_trunc('week', taken_at) ORDER BY taken_at DESC, id DESC)" +
                     "), keep AS (" +
                     "  UPDATE scan_snapshots s SET added = g.sum_added, resolved = g.sum_resolved" +
                     "  FROM grp g WHERE s.id = g.id AND g.rn = 1 AND g.cnt > 1" +
                     ") " +
                     "DELETE FROM scan_snapshots s USING grp g WHERE s.id = g.id AND g.rn > 1";
        return jdbcTemplate.update(sql, days);
    }

    /** @return silinen satır sayısı (en fazla {@code limit}) */
    public int deleteOlderThanMonths(int months, int limit) {
        return jdbcTemplate.update(
                "DELETE FROM scan_snapshots WHERE id IN (SELECT id FROM scan_snapshots " +
                "WHERE taken_at < NOW() - (? * INTERVAL '1 month') ORDER BY id LIMIT ?)", months, limit);
    }
}
