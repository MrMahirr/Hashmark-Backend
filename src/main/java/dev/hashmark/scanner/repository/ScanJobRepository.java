package dev.hashmark.scanner.repository;

import dev.hashmark.scanner.model.ScanJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class ScanJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScanJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<ScanJob> scanJobRowMapper = (rs, rowNum) -> ScanJob.builder()
            .id(rs.getLong("id"))
            .repoId(rs.getLong("repo_id"))
            .status(rs.getString("status"))
            .startedAt(rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toLocalDateTime() : null)
            .finishedAt(rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toLocalDateTime() : null)
            .debtFound(rs.getInt("debt_found"))
            .filesScanned(rs.getInt("files_scanned"))
            .filesSkipped(rs.getInt("files_skipped"))
            .filesFailed(rs.getInt("files_failed"))
            .message(rs.getString("message"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    public ScanJob save(ScanJob job) {
        String sql = "INSERT INTO scan_jobs (repo_id, status, started_at) " +
                     "VALUES (?, 'RUNNING', NOW()) RETURNING *";
                     
        return jdbcTemplate.queryForObject(sql, scanJobRowMapper, job.getRepoId());
    }

    public void updateProgress(Long jobId, int filesProcessed) {
        String sql = "UPDATE scan_jobs SET files_scanned = ? WHERE id = ? AND status = 'RUNNING'";
        jdbcTemplate.update(sql, filesProcessed, jobId);
    }

    public void complete(Long jobId, String status, int debtFound, int filesScanned, int filesSkipped,
                         int filesFailed, String message) {
        String sql = "UPDATE scan_jobs SET status = ?, debt_found = ?, files_scanned = ?, files_skipped = ?, " +
                     "files_failed = ?, message = ?, finished_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql, status, debtFound, filesScanned, filesSkipped, filesFailed, message, jobId);
    }

    /** Belirli süreden uzun süredir RUNNING görünen (takılı kalmış) işleri başarısız say. */
    public int failStaleRunningJobs(Long repoId, long staleMinutes) {
        String sql = "UPDATE scan_jobs SET status = 'FAILED', finished_at = NOW(), message = 'Timed out' " +
                     "WHERE repo_id = ? AND status = 'RUNNING' AND started_at < NOW() - (? * INTERVAL '1 minute')";
        return jdbcTemplate.update(sql, repoId, staleMinutes);
    }

    /** Uygulama yeniden başlarken yarım kalan tüm işleri kapat. */
    public int failAllRunningJobs() {
        String sql = "UPDATE scan_jobs SET status = 'FAILED', finished_at = NOW(), message = 'Interrupted by restart' " +
                     "WHERE status = 'RUNNING'";
        return jdbcTemplate.update(sql);
    }

    public Optional<ScanJob> findLatestByRepoId(Long repoId) {
        String sql = "SELECT * FROM scan_jobs WHERE repo_id = ? ORDER BY created_at DESC LIMIT 1";
        return jdbcTemplate.query(sql, scanJobRowMapper, repoId).stream().findFirst();
    }
}
