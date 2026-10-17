package dev.hashmark.debt.repository;

import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.dto.DebtStatsDto;
import dev.hashmark.debt.dto.PageResponse;
import dev.hashmark.debt.dto.ReplaceResult;
import dev.hashmark.debt.model.Debt;
import dev.hashmark.debt.util.PermalinkBuilder;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class DebtRepository {

    private static final int SAVE_BATCH_SIZE = 1000;

    /** Kullanıcının hâlâ "aktif" saydığı borç: çözülmemiş ve yok sayılmamış. */
    public static final String ACTIVE = "d.resolved_at IS NULL AND d.triage_status = 'OPEN'";

    private final JdbcTemplate jdbcTemplate;

    public DebtRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static LocalDateTime ts(Timestamp t) {
        return t != null ? t.toLocalDateTime() : null;
    }

    private final RowMapper<DebtDto> debtDtoRowMapper = (rs, rowNum) -> {
        String repoFullName = rs.getString("repo_full_name");
        String sha = rs.getString("last_seen_sha");
        String filePath = rs.getString("file_path");
        int lineNo = rs.getInt("line_no");
        String manual = rs.getString("assigned_to");
        java.sql.Date due = rs.getDate("due_date");
        return DebtDto.builder()
                .id(rs.getLong("id"))
                .repoId(rs.getLong("repo_id"))
                .repoFullName(repoFullName)
                .filePath(filePath)
                .lineNo(lineNo)
                .label(rs.getString("label"))
                .content(rs.getString("content"))
                .detectedAt(ts(rs.getTimestamp("detected_at")))
                .resolvedAt(ts(rs.getTimestamp("resolved_at")))
                .manuallyResolved(rs.getBoolean("manually_resolved"))
                .commitSha(sha)
                .permalink(PermalinkBuilder.build(repoFullName, sha, filePath, lineNo))
                .fingerprint(rs.getString("fingerprint"))
                .triageStatus(rs.getString("triage_status"))
                .assignee(manual != null ? manual : rs.getString("parsed_assignee"))
                .priority(rs.getString("priority"))
                .dueDate(due != null ? due.toLocalDate() : null)
                .issueRef(rs.getString("issue_ref"))
                .authorLogin(rs.getString("author_login"))
                .authorName(rs.getString("author_name"))
                .introducedAt(ts(rs.getTimestamp("introduced_at")))
                .build();
    };

    // ------------------------------------------------------------------
    // Tarama sonucunu uygulama
    // ------------------------------------------------------------------

    /**
     * Tarama sonucunu tek transaction'da uygular.
     *
     * <ol>
     *   <li>Bulunan her borç kimliğiyle (dosya + parmak izi + sıra) eklenir veya güncellenir;
     *       satır numarası kaymış olsa da aynı kayıt (id, detected_at, triyaj, blame) korunur.</li>
     *   <li>Bu taramada görülmeyen açık borçlar çözülür.</li>
     * </ol>
     *
     * @param onlyPaths    {@code null} değilse yalnızca bu dosyalardaki borçlar çözülebilir (kısmi tarama)
     * @param excludePaths {@code null} değilse bu dosyalardaki borçlar çözülmez (okunamayan dosyalar)
     */
    @Transactional
    public ReplaceResult replaceForRepo(Long repoId, List<Debt> debts, String commitSha,
                                        Collection<String> onlyPaths, Collection<String> excludePaths) {
        // Aynı değer hem "görüldü" damgası hem çözme eşiği olarak kullanılır (veritabanı saatine bağlı değil).
        Timestamp scanTime = Timestamp.valueOf(LocalDateTime.now());

        int before = countActive(repoId);
        int manualBefore = countManuallyResolved(repoId);
        upsertAll(repoId, debts, commitSha, scanTime);
        // Elle "çözüldü" denmiş ama kod hâlâ duruyorsa upsert yeniden açmıştır
        int reopened = Math.max(0, manualBefore - countManuallyResolved(repoId));
        int resolved = resolveUnseen(repoId, scanTime, onlyPaths, excludePaths);
        Map<String, Integer> byLabel = countActiveByLabel(repoId);
        int after = byLabel.values().stream().mapToInt(Integer::intValue).sum();

        int added = Math.max(0, after - before + resolved);
        return new ReplaceResult(added, resolved, after, byLabel, reopened);
    }

    private int countManuallyResolved(Long repoId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debts WHERE repo_id = ? AND manually_resolved AND resolved_at IS NOT NULL",
                Integer.class, repoId);
        return n == null ? 0 : n;
    }

    private void upsertAll(Long repoId, List<Debt> debts, String commitSha, Timestamp scanTime) {
        if (debts.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO debts (repo_id, file_path, line_no, label, content, fingerprint, occurrence, " +
                     "last_seen_at, last_seen_sha, parsed_assignee, priority, due_date, issue_ref) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (repo_id, file_path, fingerprint, occurrence) DO UPDATE SET " +
                     "line_no = EXCLUDED.line_no, label = EXCLUDED.label, content = EXCLUDED.content, " +
                     "last_seen_at = EXCLUDED.last_seen_at, last_seen_sha = EXCLUDED.last_seen_sha, " +
                     "parsed_assignee = EXCLUDED.parsed_assignee, priority = EXCLUDED.priority, " +
                     "due_date = EXCLUDED.due_date, issue_ref = EXCLUDED.issue_ref, " +
                     // çözülmüş borç yeniden ortaya çıkarsa yaşı ve blame bilgisi sıfırlanır
                     "detected_at = CASE WHEN debts.resolved_at IS NOT NULL THEN NOW() ELSE debts.detected_at END, " +
                     "blame_at = CASE WHEN debts.resolved_at IS NOT NULL THEN NULL ELSE debts.blame_at END, " +
                     "author_login = CASE WHEN debts.resolved_at IS NOT NULL THEN NULL ELSE debts.author_login END, " +
                     "author_name = CASE WHEN debts.resolved_at IS NOT NULL THEN NULL ELSE debts.author_name END, " +
                     "introduced_at = CASE WHEN debts.resolved_at IS NOT NULL THEN NULL ELSE debts.introduced_at END, " +
                     "introduced_sha = CASE WHEN debts.resolved_at IS NOT NULL THEN NULL ELSE debts.introduced_sha END, " +
                     "manually_resolved = FALSE, " +
                     "resolved_at = NULL";

        for (int from = 0; from < debts.size(); from += SAVE_BATCH_SIZE) {
            List<Debt> chunk = debts.subList(from, Math.min(from + SAVE_BATCH_SIZE, debts.size()));
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Debt debt = chunk.get(i);
                    ps.setLong(1, repoId);
                    ps.setString(2, debt.getFilePath());
                    ps.setInt(3, debt.getLineNo());
                    ps.setString(4, debt.getLabel());
                    ps.setString(5, debt.getContent());
                    ps.setString(6, debt.getFingerprint());
                    ps.setInt(7, debt.getOccurrence() != null ? debt.getOccurrence() : 0);
                    ps.setTimestamp(8, scanTime);
                    ps.setString(9, commitSha);
                    ps.setString(10, debt.getParsedAssignee());
                    ps.setString(11, debt.getPriority());
                    LocalDate due = debt.getDueDate();
                    if (due != null) {
                        ps.setDate(12, java.sql.Date.valueOf(due));
                    } else {
                        ps.setNull(12, Types.DATE);
                    }
                    ps.setString(13, debt.getIssueRef());
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
    }

    private int resolveUnseen(Long repoId, Timestamp scanTime, Collection<String> onlyPaths,
                              Collection<String> excludePaths) {
        StringBuilder sql = new StringBuilder(
                "UPDATE debts SET resolved_at = NOW() WHERE repo_id = ? AND resolved_at IS NULL " +
                "AND (last_seen_at IS NULL OR last_seen_at < ?)");
        List<Object> params = new ArrayList<>();
        params.add(repoId);
        params.add(scanTime);

        if (onlyPaths != null) {
            if (onlyPaths.isEmpty()) {
                return 0;
            }
            sql.append(" AND file_path = ANY (?)");
            params.add(onlyPaths.toArray(new String[0]));
        }
        if (excludePaths != null && !excludePaths.isEmpty()) {
            sql.append(" AND file_path <> ALL (?)");
            params.add(excludePaths.toArray(new String[0]));
        }

        return jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql.toString());
            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String[] array) {
                    ps.setArray(i + 1, connection.createArrayOf("text", array));
                } else {
                    ps.setObject(i + 1, param);
                }
            }
            return ps;
        });
    }

    private int countActive(Long repoId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debts d WHERE d.repo_id = ? AND " + ACTIVE, Integer.class, repoId);
        return count != null ? count : 0;
    }

    private Map<String, Integer> countActiveByLabel(Long repoId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT d.label, COUNT(*) AS cnt FROM debts d WHERE d.repo_id = ? AND " + ACTIVE + " GROUP BY d.label",
                rs -> {
                    result.put(rs.getString("label"), rs.getInt("cnt"));
                }, repoId);
        return result;
    }

    // ------------------------------------------------------------------
    // Blame zenginleştirme
    // ------------------------------------------------------------------

    /** Açık, henüz blame sorgulanmamış borcu olan dosyalar (yalnızca borç etiketleri; .hashmark.yml özel etiketleri dahil). */
    public List<String> findFilesNeedingBlame(Long repoId, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT d.file_path FROM debts d WHERE d.repo_id = ? AND d.resolved_at IS NULL " +
                "AND d.blame_at IS NULL AND d.label NOT IN ('NOTE', 'DOC', 'INFO') " +
                "GROUP BY d.file_path ORDER BY MIN(d.id) LIMIT ?",
                String.class, repoId, limit);
    }

    public record LineRef(long id, int lineNo) {
    }

    public List<LineRef> findLinesNeedingBlame(Long repoId, String filePath) {
        return jdbcTemplate.query(
                "SELECT d.id, d.line_no FROM debts d WHERE d.repo_id = ? AND d.file_path = ? " +
                "AND d.resolved_at IS NULL AND d.blame_at IS NULL AND d.label NOT IN ('NOTE', 'DOC', 'INFO')",
                (rs, i) -> new LineRef(rs.getLong("id"), rs.getInt("line_no")), repoId, filePath);
    }

    public void updateBlame(long debtId, String authorLogin, String authorName, LocalDateTime introducedAt,
                            String introducedSha) {
        jdbcTemplate.update(
                "UPDATE debts SET author_login = ?, author_name = ?, introduced_at = ?, introduced_sha = ?, " +
                "blame_at = NOW() WHERE id = ?",
                authorLogin, authorName, introducedAt != null ? Timestamp.valueOf(introducedAt) : null,
                introducedSha, debtId);
    }

    /** Blame bulunamadıysa (satır aralığı yok) tekrar tekrar sorgulanmasın. */
    public void markBlameAttempted(long debtId) {
        jdbcTemplate.update("UPDATE debts SET blame_at = NOW() WHERE id = ?", debtId);
    }

    // ------------------------------------------------------------------
    // Triyaj
    // ------------------------------------------------------------------

    /**
     * @param status      {@code null} ise değişmez
     * @param assignee    {@code null} ise değişmez, boş string ise atama kaldırılır
     * @return güncellenen borç; kullanıcıya ait değilse boş
     */
    public Optional<DebtDto> updateTriage(Long userId, Long debtId, String status, String assignee) {
        StringBuilder set = new StringBuilder();
        List<Object> params = new ArrayList<>();
        if ("RESOLVED".equals(status)) {
            // Elle çözüldü: triyaj açık kalır, kayıt çözülmüş görünür
            set.append("triage_status = 'OPEN', manually_resolved = (manually_resolved OR resolved_at IS NULL), ")
               .append("resolved_at = COALESCE(resolved_at, NOW())");
        } else if (status != null) {
            set.append("triage_status = ?");
            params.add(status);
            if ("OPEN".equals(status)) {
                // Yeniden aç: yalnızca elle çözülmüşse çözümü geri al
                set.append(", resolved_at = CASE WHEN manually_resolved THEN NULL ELSE resolved_at END, ")
                   .append("manually_resolved = FALSE");
            }
        }
        if (assignee != null) {
            if (set.length() > 0) {
                set.append(", ");
            }
            set.append("assigned_to = ?");
            params.add(assignee.isBlank() ? null : assignee.trim());
        }
        if (set.length() == 0) {
            return findByIdForUser(userId, debtId);
        }

        params.add(debtId);
        params.add(userId);
        int updated = jdbcTemplate.update(
                "UPDATE debts SET " + set + " WHERE id = ? AND repo_id IN (SELECT id FROM repos WHERE user_id = ?)",
                params.toArray());
        return updated == 0 ? Optional.empty() : findByIdForUser(userId, debtId);
    }

    public Optional<DebtDto> findByIdForUser(Long userId, Long debtId) {
        String sql = "SELECT d.*, r.full_name AS repo_full_name FROM debts d JOIN repos r ON d.repo_id = r.id " +
                     "WHERE d.id = ? AND r.user_id = ?";
        return jdbcTemplate.query(sql, debtDtoRowMapper, debtId, userId).stream().findFirst();
    }

    // ------------------------------------------------------------------
    // Sorgular
    // ------------------------------------------------------------------

    private String buildWhere(Long userId, DebtFilterRequest filter, List<Object> params) {
        StringBuilder where = new StringBuilder(" WHERE r.user_id = ? ");
        params.add(userId);

        if (filter.getRepoId() != null) {
            where.append(" AND d.repo_id = ? ");
            params.add(filter.getRepoId());
        }
        if (filter.getLabel() != null && !filter.getLabel().isBlank()) {
            where.append(" AND d.label = ? ");
            params.add(filter.getLabel().trim().toUpperCase());
        }
        if (filter.getPriority() != null && !filter.getPriority().isBlank()) {
            where.append(" AND d.priority = ? ");
            params.add(filter.getPriority().trim().toUpperCase());
        }
        if (filter.getAssignee() != null && !filter.getAssignee().isBlank()) {
            where.append(" AND COALESCE(d.assigned_to, d.parsed_assignee) = ? ");
            params.add(filter.getAssignee().trim());
        }
        if (filter.getQ() != null && !filter.getQ().isBlank()) {
            String escaped = filter.getQ().trim()
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            where.append(" AND (d.content ILIKE ? ESCAPE '\\' OR d.file_path ILIKE ? ESCAPE '\\') ");
            params.add("%" + escaped + "%");
            params.add("%" + escaped + "%");
        }

        String status = filter.getStatus() == null ? "" : filter.getStatus().trim().toLowerCase();
        switch (status) {
            case "open" -> where.append(" AND d.resolved_at IS NULL AND d.triage_status = 'OPEN' ");
            case "resolved" -> where.append(" AND d.resolved_at IS NOT NULL ");
            case "ignored" -> where.append(" AND d.resolved_at IS NULL AND d.triage_status <> 'OPEN' ");
            default -> { /* hepsi */ }
        }
        return where.toString();
    }

    public PageResponse<DebtDto> findByFilter(Long userId, DebtFilterRequest filter) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(userId, filter, params);

        String countSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + where;
        Long totalElements = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        if (totalElements == null) totalElements = 0L;

        int size = filter.safeSize();
        int page = filter.safePage();

        String dataSql = "SELECT d.*, r.full_name AS repo_full_name " +
                         "FROM debts d JOIN repos r ON d.repo_id = r.id " + where +
                         " ORDER BY " + filter.sortColumn() + " " + filter.sortDirection() + " NULLS LAST, d.id DESC" +
                         " LIMIT ? OFFSET ?";

        List<Object> dataParams = new ArrayList<>(params);
        dataParams.add(size);
        dataParams.add((long) page * size);

        List<DebtDto> content = jdbcTemplate.query(dataSql, debtDtoRowMapper, dataParams.toArray());
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return PageResponse.<DebtDto>builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    /** Dışa aktarma: sayfalama yok, en fazla {@code max} kayıt. */
    public List<DebtDto> findForExport(Long userId, DebtFilterRequest filter, int max) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(userId, filter, params);
        String sql = "SELECT d.*, r.full_name AS repo_full_name FROM debts d JOIN repos r ON d.repo_id = r.id " +
                     where + " ORDER BY " + filter.sortColumn() + " " + filter.sortDirection() +
                     " NULLS LAST, d.id DESC LIMIT ?";
        params.add(max);
        return jdbcTemplate.query(sql, debtDtoRowMapper, params.toArray());
    }

    public DebtStatsDto getStats(Long userId, Long repoId) {
        StringBuilder baseWhere = new StringBuilder(" WHERE r.user_id = ? ");
        List<Object> params = new ArrayList<>();
        params.add(userId);

        if (repoId != null) {
            baseWhere.append(" AND d.repo_id = ? ");
            params.add(repoId);
        }

        String totalSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + baseWhere +
                          " AND " + ACTIVE;
        Integer total = jdbcTemplate.queryForObject(totalSql, Integer.class, params.toArray());

        String addedSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + baseWhere +
                          " AND d.detected_at >= date_trunc('week', NOW())";
        Integer addedThisWeek = jdbcTemplate.queryForObject(addedSql, Integer.class, params.toArray());

        String resolvedSql = "SELECT COUNT(*) FROM debts d JOIN repos r ON d.repo_id = r.id " + baseWhere +
                             " AND d.resolved_at >= date_trunc('week', NOW())";
        Integer resolvedThisWeek = jdbcTemplate.queryForObject(resolvedSql, Integer.class, params.toArray());

        return DebtStatsDto.builder()
                .total(total != null ? total : 0)
                .addedThisWeek(addedThisWeek != null ? addedThisWeek : 0)
                .resolvedThisWeek(resolvedThisWeek != null ? resolvedThisWeek : 0)
                .build();
    }
}
