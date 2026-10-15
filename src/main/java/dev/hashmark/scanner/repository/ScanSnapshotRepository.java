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

    public void save(Long repoId, Long scanJobId, String commitSha, int totalOpen,
                     Map<String, Integer> labelCounts, int added, int resolved) {
        String sql = "INSERT INTO scan_snapshots (repo_id, scan_job_id, commit_sha, total_open, " +
                     "todo_count, fixme_count, hack_count, xxx_count, note_count, doc_count, info_count, " +
                     "added, resolved) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
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
}
