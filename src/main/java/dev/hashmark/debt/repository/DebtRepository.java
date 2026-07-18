package dev.hashmark.debt.repository;

import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.dto.DebtStatsDto;
import dev.hashmark.debt.dto.PageResponse;
import dev.hashmark.debt.model.Debt;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class DebtRepository {

    private final JdbcTemplate jdbcTemplate;

    public DebtRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<DebtDto> debtDtoRowMapper = (rs, rowNum) -> DebtDto.builder()
            .id(rs.getLong("id"))
            .repoId(rs.getLong("repo_id"))
            .repoFullName(rs.getString("repo_full_name"))
            .filePath(rs.getString("file_path"))
            .lineNo(rs.getInt("line_no"))
            .label(rs.getString("label"))
            .content(rs.getString("content"))
            .detectedAt(rs.getTimestamp("detected_at") != null ? rs.getTimestamp("detected_at").toLocalDateTime() : null)
            .resolvedAt(rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toLocalDateTime() : null)
            .build();

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

    public PageResponse<DebtDto> findByFilter(Long userId, DebtFilterRequest filter) {
        StringBuilder whereClause = new StringBuilder(" WHERE r.user_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (filter.getRepoId() != null) {
            whereClause.append(" AND d.repo_id = ? ");
            params.add(filter.getRepoId());
        }

        if (filter.getLabel() != null && !filter.getLabel().isBlank()) {
            whereClause.append(" AND d.label = ? ");
            params.add(filter.getLabel());
        }

        if ("open".equalsIgnoreCase(filter.getStatus())) {
            whereClause.append(" AND d.resolved_at IS NULL ");
        } else if ("resolved".equalsIgnoreCase(filter.getStatus())) {
            whereClause.append(" AND d.resolved_at IS NOT NULL ");
        }

        String countSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + whereClause;
        Long totalElements = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        if (totalElements == null) totalElements = 0L;

        String dataSql = "SELECT d.*, r.full_name as repo_full_name " +
                         "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                         whereClause +
                         " ORDER BY d.detected_at DESC LIMIT ? OFFSET ?";
        
        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(filter.getSize());
        dataParams.add(filter.getPage() * filter.getSize());

        List<DebtDto> content = jdbcTemplate.query(dataSql, debtDtoRowMapper, dataParams.toArray());

        int totalPages = (int) Math.ceil((double) totalElements / filter.getSize());

        return PageResponse.<DebtDto>builder()
                .content(content)
                .page(filter.getPage())
                .size(filter.getSize())
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    public DebtStatsDto getStats(Long userId, Long repoId) {
        StringBuilder baseWhere = new StringBuilder(" WHERE r.user_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (repoId != null) {
            baseWhere.append(" AND d.repo_id = ? ");
            params.add(repoId);
        }

        String totalSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + baseWhere + " AND d.resolved_at IS NULL";
        Integer total = jdbcTemplate.queryForObject(totalSql, Integer.class, params.toArray());

        String addedSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + baseWhere + " AND d.detected_at >= date_trunc('week', NOW())";
        Integer addedThisWeek = jdbcTemplate.queryForObject(addedSql, Integer.class, params.toArray());

        String resolvedSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + baseWhere + " AND d.resolved_at >= date_trunc('week', NOW())";
        Integer resolvedThisWeek = jdbcTemplate.queryForObject(resolvedSql, Integer.class, params.toArray());

        return DebtStatsDto.builder()
                .total(total != null ? total : 0)
                .addedThisWeek(addedThisWeek != null ? addedThisWeek : 0)
                .resolvedThisWeek(resolvedThisWeek != null ? resolvedThisWeek : 0)
                .build();
    }
}
