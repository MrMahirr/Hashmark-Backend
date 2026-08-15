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
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    public ScanJob save(ScanJob job) {
        String sql = "INSERT INTO scan_jobs (repo_id, status, started_at) " +
                     "VALUES (?, 'RUNNING', NOW()) RETURNING *";
                     
        return jdbcTemplate.queryForObject(sql, scanJobRowMapper, job.getRepoId());
    }

    public void updateStatus(Long jobId, String status, Integer debtFound) {
        String sql = "UPDATE scan_jobs SET status = ?, debt_found = ?, finished_at = NOW() WHERE id = ?";
        jdbcTemplate.update(sql, status, debtFound, jobId);
    }

    public Optional<ScanJob> findLatestByRepoId(Long repoId) {
        String sql = "SELECT * FROM scan_jobs WHERE repo_id = ? ORDER BY created_at DESC LIMIT 1";
        return jdbcTemplate.query(sql, scanJobRowMapper, repoId).stream().findFirst();
    }
}
