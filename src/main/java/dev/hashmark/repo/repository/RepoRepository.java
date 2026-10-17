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
            .tracked(rs.getBoolean("tracked"))
            .sizeKb(rs.getObject("size_kb") != null ? rs.getInt("size_kb") : null)
            .build();

    /** Kullanıcı kotası: izlenen depo sayısı. */
    public int countTrackedByUserId(Long userId) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM repos WHERE user_id = ? AND tracked",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    public List<Repo> findAllByUserId(Long userId) {
        String sql = "SELECT * FROM repos WHERE user_id = ? AND tracked ORDER BY full_name ASC";
        return jdbcTemplate.query(sql, repoRowMapper, userId);
    }

    public List<Repo> findAllByUserIdWithDebtCount(Long userId) {
        String sql = "SELECT r.*, (SELECT COUNT(*) FROM debts d WHERE d.repo_id = r.id AND d.resolved_at IS NULL AND d.triage_status = 'OPEN') as debt_count " +
                     "FROM repos r WHERE r.user_id = ? AND r.tracked ORDER BY r.full_name ASC";
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
                     "FROM repos r WHERE r.id = ? AND r.user_id = ? AND r.tracked";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Repo repo = repoRowMapper.mapRow(rs, rowNum);
            if (repo != null) {
                repo.setDebtCount(rs.getInt("debt_count"));
            }
            return repo;
        }, id, userId).stream().findFirst();
    }

    public Repo save(Repo repo) {
        // Yeni depo aday olarak gelir (tracked = FALSE); mevcut kaydın tracked değeri korunur.
        String sql = "INSERT INTO repos (user_id, github_repo_id, full_name, private, tracked, size_kb) " +
                     "VALUES (?, ?, ?, ?, FALSE, ?) " +
                     "ON CONFLICT (user_id, github_repo_id) DO UPDATE " +
                     "SET full_name = EXCLUDED.full_name, private = EXCLUDED.private, " +
                     "size_kb = COALESCE(EXCLUDED.size_kb, repos.size_kb) " +
                     "RETURNING *";

        return jdbcTemplate.queryForObject(
                sql,
                repoRowMapper,
                repo.getUserId(),
                repo.getGithubRepoId(),
                repo.getFullName(),
                repo.getIsPrivate(),
                repo.getSizeKb()
        );
    }

    /** Henüz izlenmeyen (GitHub'dan gelen) depolar. */
    public List<Repo> findCandidatesByUserId(Long userId) {
        return jdbcTemplate.query("SELECT * FROM repos WHERE user_id = ? AND NOT tracked ORDER BY full_name ASC",
                repoRowMapper, userId);
    }

    /** Depoyu izlemeye alır; kullanıcıya ait değilse 0 döner. */
    public int markTracked(Long id, Long userId) {
        return jdbcTemplate.update("UPDATE repos SET tracked = TRUE WHERE id = ? AND user_id = ?", id, userId);
    }

    /** Kullanıcının tüm depoları ve bağlı borç/iş kayıtları (FK cascade). */
    public int deleteAllByUserId(Long userId) {
        return jdbcTemplate.update("DELETE FROM repos WHERE user_id = ?", userId);
    }

    /** Kullanıcının izlenen depoları için açık borçların etiket kırılımı: depo -> (etiket -> sayı). */
    public java.util.Map<Long, java.util.Map<String, Integer>> findOpenBreakdownByUserId(Long userId) {
        java.util.Map<Long, java.util.Map<String, Integer>> result = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT d.repo_id, d.label, COUNT(*) AS n FROM debts d JOIN repos r ON r.id = d.repo_id " +
                "WHERE r.user_id = ? AND r.tracked AND d.resolved_at IS NULL AND d.triage_status = 'OPEN' " +
                "GROUP BY d.repo_id, d.label",
                rs -> {
                    result.computeIfAbsent(rs.getLong("repo_id"), k -> new java.util.LinkedHashMap<>())
                            .put(rs.getString("label"), rs.getInt("n"));
                },
                userId);
        return result;
    }

    /** @return silinen satır; başka kullanıcının ya da olmayan depo için 0 */
    public int deleteByIdAndUserId(Long id, Long userId) {
        String sql = "DELETE FROM repos WHERE id = ? AND user_id = ?";
        return jdbcTemplate.update(sql, id, userId);
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
