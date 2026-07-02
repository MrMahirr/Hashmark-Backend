package dev.hashmark.debt.repository;

import dev.hashmark.debt.model.Debt;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Repository
public class DebtRepository {

    private final JdbcTemplate jdbcTemplate;

    public DebtRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void markAllAsResolvedForRepo(Long repoId) {
        String sql = "UPDATE debts SET resolved_at = NOW() WHERE repo_id = ? AND resolved_at IS NULL";
        jdbcTemplate.update(sql, repoId);
    }

    public void saveAll(Long repoId, List<Debt> debts) {
        if (debts.isEmpty()) return;

        String sql = "INSERT INTO debts (repo_id, file_path, line_no, label, content) " +
                     "VALUES (?, ?, ?, ?, ?) " +
                     "ON CONFLICT (repo_id, file_path, line_no) DO UPDATE " +
                     "SET content = EXCLUDED.content, resolved_at = NULL";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Debt debt = debts.get(i);
                ps.setLong(1, repoId);
                ps.setString(2, debt.getFilePath());
                ps.setInt(3, debt.getLineNo());
                ps.setString(4, debt.getLabel());
                ps.setString(5, debt.getContent());
            }

            @Override
            public int getBatchSize() {
                return debts.size();
            }
        });
    }
}
