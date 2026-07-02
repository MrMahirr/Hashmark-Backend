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
            .build();

    public List<Repo> findAllByUserId(Long userId) {
        String sql = "SELECT * FROM repos WHERE user_id = ? ORDER BY full_name ASC";
        return jdbcTemplate.query(sql, repoRowMapper, userId);
    }

    public Optional<Repo> findByIdAndUserId(Long id, Long userId) {
        String sql = "SELECT * FROM repos WHERE id = ? AND user_id = ?";
        return jdbcTemplate.query(sql, repoRowMapper, id, userId).stream().findFirst();
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
}
