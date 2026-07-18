package dev.hashmark.report.repository;

import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.ModuleDebtInfo;
import dev.hashmark.report.dto.TrendDataPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ReportRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TrendDataPoint> getTrendData(Long userId, Long repoId) {
        Map<LocalDate, TrendDataPoint> pointMap = new HashMap<>();

        // New debts query
        StringBuilder newDebtsSql = new StringBuilder(
                "SELECT date_trunc('week', d.detected_at) as week_start, COUNT(*) as cnt " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "WHERE r.user_id = ? AND d.detected_at >= NOW() - INTERVAL '8 weeks' "
        );
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (repoId != null) {
            newDebtsSql.append(" AND d.repo_id = ? ");
            params.add(repoId);
        }
        newDebtsSql.append(" GROUP BY week_start");

        jdbcTemplate.query(newDebtsSql.toString(), (rs) -> {
            if (rs.getDate("week_start") != null) {
                LocalDate ws = rs.getDate("week_start").toLocalDate();
                int cnt = rs.getInt("cnt");
                pointMap.putIfAbsent(ws, new TrendDataPoint(ws, 0, 0, 0));
                pointMap.get(ws).setNewDebts(cnt);
            }
        }, params.toArray());

        // Resolved debts query
        StringBuilder resDebtsSql = new StringBuilder(
                "SELECT date_trunc('week', d.resolved_at) as week_start, COUNT(*) as cnt " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "WHERE r.user_id = ? AND d.resolved_at >= NOW() - INTERVAL '8 weeks' "
        );
        List<Object> resParams = new ArrayList<>();
        resParams.add(userId);
        if (repoId != null) {
            resDebtsSql.append(" AND d.repo_id = ? ");
            resParams.add(repoId);
        }
        resDebtsSql.append(" GROUP BY week_start");

        jdbcTemplate.query(resDebtsSql.toString(), (rs) -> {
            if (rs.getDate("week_start") != null) {
                LocalDate ws = rs.getDate("week_start").toLocalDate();
                int cnt = rs.getInt("cnt");
                pointMap.putIfAbsent(ws, new TrendDataPoint(ws, 0, 0, 0));
                pointMap.get(ws).setResolvedDebts(cnt);
            }
        }, resParams.toArray());

        List<TrendDataPoint> result = new ArrayList<>(pointMap.values());
        result.sort((p1, p2) -> p1.getWeekStart().compareTo(p2.getWeekStart()));
        return result;
    }

    public LabelStats getLabelStats(Long userId, Long repoId) {
        StringBuilder sql = new StringBuilder(
                "SELECT label, COUNT(*) as cnt " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "WHERE r.user_id = ? AND d.resolved_at IS NULL "
        );
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (repoId != null) {
            sql.append(" AND d.repo_id = ? ");
            params.add(repoId);
        }
        sql.append(" GROUP BY label");

        LabelStats stats = new LabelStats(0, 0, 0, 0);
        jdbcTemplate.query(sql.toString(), (rs) -> {
            String label = rs.getString("label");
            int count = rs.getInt("cnt");
            if (label == null) return;
            switch (label.toUpperCase()) {
                case "TODO": stats.setTodoCount(count); break;
                case "FIXME": stats.setFixmeCount(count); break;
                case "HACK": stats.setHackCount(count); break;
                case "XXX": stats.setXxxCount(count); break;
            }
        }, params.toArray());
        
        return stats;
    }

    public List<ModuleDebtInfo> getTopModules(Long userId, Long repoId) {
        StringBuilder sql = new StringBuilder(
                "SELECT split_part(file_path, '/', 1) as module, COUNT(*) as cnt " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "WHERE r.user_id = ? AND d.resolved_at IS NULL "
        );
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (repoId != null) {
            sql.append(" AND d.repo_id = ? ");
            params.add(repoId);
        }
        sql.append(" GROUP BY module ORDER BY cnt DESC LIMIT 5");

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> ModuleDebtInfo.builder()
                .modulePath(rs.getString("module"))
                .debtCount(rs.getInt("cnt"))
                .build()
        , params.toArray());
    }
}
