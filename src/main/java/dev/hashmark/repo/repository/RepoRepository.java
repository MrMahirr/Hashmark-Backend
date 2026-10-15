package dev.hashmark.repo.repository;

import dev.hashmark.repo.model.Repo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class RepoRepository {

    private final JdbcTemplate jdbcTemplate;

    public RepoRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Repo> repoRowMapper = (rs, rowNum) -> Repo.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .githubRepoId(rs.getString("github_repo_id"))
            .fullName(rs.getString("full_name"))
            .isPrivate(rs.getBoolean("private"))
            .lastScannedAt(rs.getTimestamp("last_scanned_at") != null ? rs.getTimestamp("last_scanned_at").toLocalDateTime() : null)
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .trackedBranch(rs.getString("tracked_branch"))
            .lastScannedSha(rs.getString("last_scanned_sha"))
            .build();

    public List<Repo> findAllByUserId(Long userId) {
        String sql = "SELECT * FROM repos WHERE user_id = ? ORDER BY full_name ASC";
        return jdbcTemplate.query(sql, repoRowMapper, userId);
    }

    public List<Repo> findAllByUserIdWithDebtCount(Long userId) {
        String sql = "SELECT r.*, (SELECT COUNT(*) FROM debts d WHERE d.repo_id = r.id AND d.resolved_at IS NULL AND d.triage_status = 'OPEN') as debt_count " +
                     "FROM repos r WHERE r.user_id = ? ORDER BY r.full_name ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Repo repo = repoRowMapper.mapRow(rs, rowNum);
            if (repo != null) {
                repo.setDebtCount(rs.getInt("debt_count"));
            }
            return repo;
        }, userId);
    }

    public Optional<Repo> findByIdAndUserId(Long id, Long userId) {
        String sql = "SELECT * FROM repos WHERE id = ? AND user_id = ?";
        return jdbcTemplate.query(sql, repoRowMapper, id, userId).stream().findFirst();
    }

    public Optional<Repo> findByIdAndUserIdWithDebtCount(Long id, Long userId) {
        String sql = "SELECT r.*, (SELECT COUNT(*) FROM debts d WHERE d.repo_id = r.id AND d.resolved_at IS NULL AND d.triage_status = 'OPEN') as debt_count " +
                     "FROM repos r WHERE r.id = ? AND r.user_id = ?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Repo repo = repoRowMapper.mapRow(rs, rowNum);
            if (repo != null) {
                repo.setDebtCount(rs.getInt("debt_count"));
            }
            return repo;
        }, id, userId).stream().findFirst();
    }

    public Repo save(Repo repo) {
        String sql = "INSERT INTO repos (user_id, github_repo_id, full_name, private) " +
                     "VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT (user_id, github_repo_id) DO UPDATE " +
                     "SET full_name = EXCLUDED.full_name, private = EXCLUDED.private " +
                     "RETURNING *";
                     
        return jdbcTemplate.queryForObject(
                sql,
                repoRowMapper,
                repo.getUserId(),
                repo.getGithubRepoId(),
                repo.getFullName(),
                repo.getIsPrivate()
        );
    }

    public void deleteByIdAndUserId(Long id, Long userId) {
        String sql = "DELETE FROM repos WHERE id = ? AND user_id = ?";
        jdbcTemplate.update(sql, id, userId);
    }

    public void updateLastScannedAt(Long repoId, LocalDateTime time) {
        String sql = "UPDATE repos SET last_scanned_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, time, repoId);
    }

    public void updateLastScannedSha(Long repoId, String sha) {
        jdbcTemplate.update("UPDATE repos SET last_scanned_sha = ? WHERE id = ?", sha, repoId);
    }

    /** @param branch {@code null} = deponun varsayılan dalı */
    public int updateTrackedBranch(Long id, Long userId, String branch) {
        return jdbcTemplate.update("UPDATE repos SET tracked_branch = ? WHERE id = ? AND user_id = ?",
                branch, id, userId);
    }
}
