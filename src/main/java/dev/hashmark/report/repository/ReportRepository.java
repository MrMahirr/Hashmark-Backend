package dev.hashmark.report.repository;

import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.ModuleDebtInfo;
import dev.hashmark.report.dto.TrendDataPoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
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

    private static final int TREND_WEEKS = 8;

    public List<TrendDataPoint> getTrendData(Long userId, Long repoId) {
        List<TrendDataPoint> fromSnapshots = getTrendFromSnapshots(userId, repoId, LocalDate.now());
        return fromSnapshots.isEmpty() ? getTrendFromDebtTimestamps(userId, repoId) : fromSnapshots;
    }

    /**
     * Tarama anlık görüntülerinden haftalık trend: her hafta için depo başına o haftaki son görüntü
     * (görüntüsü olmayan haftalarda önceki değer taşınır), eklenen/çözülen ise haftanın toplamıdır.
     */
    public List<TrendDataPoint> getTrendFromSnapshots(Long userId, Long repoId, LocalDate today) {
        LocalDate lastWeek = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate firstWeek = lastWeek.minusWeeks(TREND_WEEKS - 1L);
        Timestamp windowStart = Timestamp.valueOf(firstWeek.atStartOfDay());

        StringBuilder scope = new StringBuilder(" WHERE r.user_id = ? ");
        List<Object> scopeParams = new ArrayList<>();
        scopeParams.add(userId);
        if (repoId != null) {
            scope.append(" AND s.repo_id = ? ");
            scopeParams.add(repoId);
        }

        // Pencereden önceki son değer (taşıma için başlangıç noktası)
        Map<Long, Integer> current = new HashMap<>();
        jdbcTemplate.query(
                "SELECT DISTINCT ON (s.repo_id) s.repo_id, s.total_open FROM scan_snapshots s " +
                "JOIN repos r ON r.id = s.repo_id " + scope + " AND s.taken_at < ? " +
                "ORDER BY s.repo_id, s.taken_at DESC, s.id DESC",
                rs -> {
                    current.put(rs.getLong("repo_id"), rs.getInt("total_open"));
                },
                appendParam(scopeParams, windowStart));

        List<Object[]> rows = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT s.repo_id, s.taken_at, s.total_open, s.added, s.resolved FROM scan_snapshots s " +
                "JOIN repos r ON r.id = s.repo_id " + scope + " AND s.taken_at >= ? " +
                "ORDER BY s.taken_at ASC, s.id ASC",
                rs -> {
                    rows.add(new Object[]{rs.getLong("repo_id"), rs.getTimestamp("taken_at").toLocalDateTime().toLocalDate(),
                            rs.getInt("total_open"), rs.getInt("added"), rs.getInt("resolved")});
                },
                appendParam(scopeParams, windowStart));

        if (rows.isEmpty() && current.isEmpty()) {
            return new ArrayList<>();
        }

        List<TrendDataPoint> result = new ArrayList<>();
        int index = 0;
        boolean started = !current.isEmpty();
        for (int w = 0; w < TREND_WEEKS; w++) {
            LocalDate weekStart = firstWeek.plusWeeks(w);
            LocalDate weekEnd = weekStart.plusWeeks(1);
            int added = 0;
            int resolved = 0;
            boolean hadSnapshot = false;
            while (index < rows.size() && ((LocalDate) rows.get(index)[1]).isBefore(weekEnd)) {
                Object[] row = rows.get(index++);
                current.put((Long) row[0], (Integer) row[2]);
                added += (Integer) row[3];
                resolved += (Integer) row[4];
                hadSnapshot = true;
            }
            started = started || hadSnapshot;
            if (started) {
                int total = current.values().stream().mapToInt(Integer::intValue).sum();
                result.add(new TrendDataPoint(weekStart, total, added, resolved));
            }
        }
        return result;
    }

    private static Object[] appendParam(List<Object> params, Object extra) {
        List<Object> all = new ArrayList<>(params);
        all.add(extra);
        return all.toArray();
    }

    /** Anlık görüntü yoksa (eski veriler) borç zaman damgalarından yaklaşık trend. */
    private List<TrendDataPoint> getTrendFromDebtTimestamps(Long userId, Long repoId) {
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
                "WHERE r.user_id = ? AND d.resolved_at IS NULL AND d.triage_status = 'OPEN' "
        );
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (repoId != null) {
            sql.append(" AND d.repo_id = ? ");
            params.add(repoId);
        }
        sql.append(" GROUP BY label");

        LabelStats stats = new LabelStats(0, 0, 0, 0, 0, 0, 0);
        jdbcTemplate.query(sql.toString(), (rs) -> {
            String label = rs.getString("label");
            int count = rs.getInt("cnt");
            if (label == null) return;
            switch (label.toUpperCase()) {
                case "TODO": stats.setTodoCount(count); break;
                case "FIXME": stats.setFixmeCount(count); break;
                case "HACK": stats.setHackCount(count); break;
                case "XXX": stats.setXxxCount(count); break;
                case "NOTE": stats.setNoteCount(count); break;
                case "DOC": stats.setDocCount(count); break;
                case "INFO": stats.setInfoCount(count); break;
                default: stats.getCustomCounts().put(label.toUpperCase(), count); break;
            }
        }, params.toArray());
        
        return stats;
    }

    public List<ModuleDebtInfo> getTopModules(Long userId, Long repoId) {
        StringBuilder sql = new StringBuilder(
                "SELECT split_part(file_path, '/', 1) as module, COUNT(*) as cnt " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "WHERE r.user_id = ? AND d.resolved_at IS NULL AND d.triage_status = 'OPEN' "
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
