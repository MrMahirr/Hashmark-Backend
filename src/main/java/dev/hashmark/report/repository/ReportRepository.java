package dev.hashmark.report.repository;

import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.ModuleDebtInfo;
import dev.hashmark.report.dto.TrendDataPoint;
import dev.hashmark.report.service.DebtInsightsCalculator;
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

    /**
     * İçgörü girdisi: açık borçlar (NOTE/DOC/INFO hariç) etiket, öncelik ve yaş aralığına göre gruplanır.
     * Yaş = blame tarihi, yoksa ilk görülme; {@code olderThanDays} süzgeciyle aynı tanım, sayılar bağlantılarla tutarlı.
     */
    public DebtInsightsCalculator getInsights(Long userId, Long repoId) {
        return queryInsights(userId, repoId, false).getOrDefault(ALL_REPOS, new DebtInsightsCalculator());
    }

    /** Depo karşılaştırması: depo kimliği → içgörü girdisi (açık borcu olmayan depolar yer almaz). */
    public Map<Long, DebtInsightsCalculator> getInsightsPerRepo(Long userId) {
        return queryInsights(userId, null, true);
    }

    private static final long ALL_REPOS = 0L;

    private Map<Long, DebtInsightsCalculator> queryInsights(Long userId, Long repoId, boolean perRepo) {
        String repoColumn = perRepo ? "d.repo_id" : "0";
        StringBuilder bucket = new StringBuilder("CASE");
        int[] bounds = DebtInsightsCalculator.AGE_BOUNDS;
        for (int i = 1; i < bounds.length; i++) {
            bucket.append(" WHEN a.age < ").append(bounds[i]).append(" THEN ").append(i - 1);
        }
        bucket.append(" ELSE ").append(bounds.length - 1).append(" END");

        StringBuilder sql = new StringBuilder(
                "SELECT " + repoColumn + " AS repo_key, d.label, d.priority, " + bucket + " AS bucket, COUNT(*) AS cnt, " +
                "COUNT(*) FILTER (WHERE d.priority_auto) AS auto_cnt, " +
                "SUM(LEAST(a.age, 365)) AS capped_age, SUM(a.age) AS total_age " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "CROSS JOIN LATERAL (SELECT GREATEST(0, EXTRACT(EPOCH FROM (NOW() - COALESCE(d.introduced_at, d.detected_at)))" +
                " / 86400.0) AS age) a " +
                "WHERE r.user_id = ? AND " + DebtRepository.ACTIVE + " AND d.label NOT IN ('NOTE', 'DOC', 'INFO') ");
        List<Object> params = new ArrayList<>();
        params.add(userId);
        if (repoId != null) {
            sql.append(" AND d.repo_id = ? ");
            params.add(repoId);
        }
        sql.append(" GROUP BY repo_key, d.label, d.priority, bucket");

        Map<Long, DebtInsightsCalculator> result = new HashMap<>();
        jdbcTemplate.query(sql.toString(), rs -> {
            result.computeIfAbsent(rs.getLong("repo_key"), k -> new DebtInsightsCalculator())
                    .add(rs.getString("label"), rs.getString("priority"), rs.getInt("bucket"), rs.getInt("cnt"),
                            rs.getInt("auto_cnt"), rs.getDouble("capped_age"), rs.getDouble("total_age"));
        }, params.toArray());

        // Sırlar NOTE gibi bilgi yorumlarında da sayılır; borcu olmayan depoda yalnızca sır olabilir
        StringBuilder secretSql = new StringBuilder("SELECT " + repoColumn + " AS repo_key, COUNT(*) AS cnt " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "WHERE r.user_id = ? AND " + DebtRepository.ACTIVE + " AND d.secret_detected");
        if (repoId != null) {
            secretSql.append(" AND d.repo_id = ?");
        }
        secretSql.append(" GROUP BY repo_key");
        jdbcTemplate.query(secretSql.toString(), rs -> {
            result.computeIfAbsent(rs.getLong("repo_key"), k -> new DebtInsightsCalculator())
                    .secretCount(rs.getInt("cnt"));
        }, params.toArray());
        return result;
    }

    /** Hotspot satırı: dosya × etiket × öncelik başına açık borç ve bunların 90+ gün olanları. */
    public record HotspotRow(String filePath, String label, String priority, int count, int stale) {
    }

    /** Hotspot girdisi için en fazla bu kadar satır okunur (çok büyük depolarda bellek sınırı). */
    public static final int MAX_HOTSPOT_ROWS = 20_000;

    /**
     * Depodaki açık borçlar (NOTE/DOC/INFO hariç), {@code prefix} klasörü altında; dosya, etiket ve önceliğe göre.
     *
     * @param prefix boş = depo kökü; aksi halde sonunda "/" olan klasör yolu
     */
    public List<HotspotRow> getHotspotRows(Long userId, Long repoId, String prefix) {
        StringBuilder sql = new StringBuilder(
                "SELECT d.file_path, d.label, d.priority, COUNT(*) AS cnt, " +
                "COUNT(*) FILTER (WHERE COALESCE(d.introduced_at, d.detected_at) <= NOW() - (? * INTERVAL '1 day')) AS stale " +
                "FROM debts d JOIN repos r ON d.repo_id = r.id " +
                "WHERE r.user_id = ? AND d.repo_id = ? AND " + DebtRepository.ACTIVE +
                " AND d.label NOT IN ('NOTE', 'DOC', 'INFO') ");
        List<Object> params = new ArrayList<>(List.of(DebtInsightsCalculator.STALE_DAYS, userId, repoId));
        if (!prefix.isEmpty()) {
            sql.append(" AND d.file_path LIKE ? ESCAPE '\\' ");
            params.add(DebtRepository.escapeLike(prefix) + "%");
        }
        sql.append(" GROUP BY d.file_path, d.label, d.priority ORDER BY cnt DESC LIMIT ?");
        params.add(MAX_HOTSPOT_ROWS + 1);
        return jdbcTemplate.query(sql.toString(), (rs, i) -> new HotspotRow(rs.getString("file_path"),
                rs.getString("label"), rs.getString("priority"), rs.getInt("cnt"), rs.getInt("stale")),
                params.toArray());
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
