package dev.hashmark.scanner.repository;

import dev.hashmark.scanner.model.ScanJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Tarama işleri aynı zamanda kalıcı kuyruktur: PENDING → RUNNING → DONE/FAILED.
 * Çalışan iş {@code heartbeat_at} ile canlılığını bildirir; kalp atışı kesilen iş yeniden kuyruğa alınır.
 */
@Repository
public class ScanJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public ScanJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static java.time.LocalDateTime ts(Timestamp t) {
        return t != null ? t.toLocalDateTime() : null;
    }

    private final RowMapper<ScanJob> scanJobRowMapper = (rs, rowNum) -> ScanJob.builder()
            .id(rs.getLong("id"))
            .repoId(rs.getLong("repo_id"))
            .userId(rs.getObject("user_id") != null ? rs.getLong("user_id") : null)
            .status(rs.getString("status"))
            .branch(rs.getString("branch"))
            .commitSha(rs.getString("commit_sha"))
            .attempts(rs.getInt("attempts"))
            .startedAt(ts(rs.getTimestamp("started_at")))
            .finishedAt(ts(rs.getTimestamp("finished_at")))
            .debtFound(rs.getInt("debt_found"))
            .filesScanned(rs.getInt("files_scanned"))
            .filesSkipped(rs.getInt("files_skipped"))
            .filesFailed(rs.getInt("files_failed"))
            .message(rs.getString("message"))
            .createdAt(ts(rs.getTimestamp("created_at")))
            .build();

    /** Yeni iş kuyruğa PENDING olarak eklenir. Repo için aktif iş varsa {@code DuplicateKeyException} fırlatır. */
    public ScanJob enqueue(Long repoId, Long userId, String branch) {
        String sql = "INSERT INTO scan_jobs (repo_id, user_id, branch, status) " +
                     "VALUES (?, ?, ?, 'PENDING') RETURNING *";
        return jdbcTemplate.queryForObject(sql, scanJobRowMapper, repoId, userId, branch);
    }

    /** Kuyruk metrikleri: PENDING sayısı. */
    public long countPending() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scan_jobs WHERE status = 'PENDING'", Long.class);
        return n == null ? 0 : n;
    }

    /** Kuyruk metrikleri: en eski PENDING işin yaşı (sn); kuyruk boşsa 0. */
    public double oldestPendingAgeSeconds() {
        Double age = jdbcTemplate.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0) FROM scan_jobs WHERE status = 'PENDING'",
                Double.class);
        return age == null ? 0 : age;
    }

    public long countRunning() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scan_jobs WHERE status = 'RUNNING'", Long.class);
        return n == null ? 0 : n;
    }

    /** Bekleyen işleri atomik olarak sahiplenir (çoklu instance'ta aynı iş iki kez alınmaz). */
    public List<ScanJob> claimPending(int limit) {
        String sql = "UPDATE scan_jobs SET status = 'RUNNING', started_at = NOW(), heartbeat_at = NOW(), " +
                     "attempts = attempts + 1 " +
                     "WHERE id IN (SELECT id FROM scan_jobs WHERE status = 'PENDING' " +
                     "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED) RETURNING *";
        return jdbcTemplate.query(sql, scanJobRowMapper, limit);
    }

    /** Sahiplenilmiş ama çalıştırılamamış işi kuyruğa geri koyar. */
    public void release(Long jobId) {
        jdbcTemplate.update("UPDATE scan_jobs SET status = 'PENDING', attempts = GREATEST(attempts - 1, 0) " +
                            "WHERE id = ? AND status = 'RUNNING'", jobId);
    }

    public void updateProgress(Long jobId, int filesProcessed) {
        String sql = "UPDATE scan_jobs SET files_scanned = ?, heartbeat_at = NOW() WHERE id = ? AND status = 'RUNNING'";
        jdbcTemplate.update(sql, filesProcessed, jobId);
    }

    public void heartbeat(Long jobId) {
        jdbcTemplate.update("UPDATE scan_jobs SET heartbeat_at = NOW() WHERE id = ? AND status = 'RUNNING'", jobId);
    }

    /**
     * İşi bitirir. Yalnızca hâlâ RUNNING olan iş kapatılır: kalp atışı kesilip yeniden kuyruğa alınmış
     * ya da başka bir çalıştırmayla bitmiş işin sonucunu geç kalan çalıştırma ezemez.
     *
     * @return iş gerçekten kapatıldıysa true
     */
    public boolean complete(Long jobId, String status, int debtFound, int filesScanned, int filesSkipped,
                            int filesFailed, String commitSha, String message) {
        String sql = "UPDATE scan_jobs SET status = ?, debt_found = ?, files_scanned = ?, files_skipped = ?, " +
                     "files_failed = ?, commit_sha = ?, message = ?, finished_at = NOW() " +
                     "WHERE id = ? AND status = 'RUNNING'";
        return jdbcTemplate.update(sql, status, debtFound, filesScanned, filesSkipped, filesFailed, commitSha,
                message, jobId) == 1;
    }

    /**
     * Kalp atışı kesilen RUNNING işleri yeniden kuyruğa alır; deneme hakkı bitenleri başarısız yapar.
     *
     * @return yeniden kuyruğa alınan + başarısız yapılan iş sayısı
     */
    public int recoverStale(long timeoutMinutes, int maxAttempts) {
        String requeue = "UPDATE scan_jobs SET status = 'PENDING', message = 'Re-queued after interruption' " +
                         "WHERE status = 'RUNNING' AND attempts < ? " +
                         "AND COALESCE(heartbeat_at, started_at, created_at) < NOW() - (? * INTERVAL '1 minute')";
        String fail = "UPDATE scan_jobs SET status = 'FAILED', finished_at = NOW(), " +
                      "message = 'Interrupted too many times' " +
                      "WHERE status = 'RUNNING' AND attempts >= ? " +
                      "AND COALESCE(heartbeat_at, started_at, created_at) < NOW() - (? * INTERVAL '1 minute')";
        return jdbcTemplate.update(requeue, maxAttempts, timeoutMinutes)
                + jdbcTemplate.update(fail, maxAttempts, timeoutMinutes);
    }

    /**
     * Saklama süresi dolmuş bitmiş işleri siler. Aktif işler ve her deponun en son işi (tarama durumu
     * ucu onu gösterir) korunur. Snapshot'lardaki {@code scan_job_id} {@code ON DELETE SET NULL} ile boşalır.
     *
     * @return silinen satır sayısı (en fazla {@code limit})
     */
    public int deleteFinishedOlderThan(int days, int limit) {
        String sql = "DELETE FROM scan_jobs WHERE id IN (" +
                     "SELECT j.id FROM scan_jobs j WHERE j.status IN ('DONE', 'FAILED') " +
                     "AND COALESCE(j.finished_at, j.created_at) < NOW() - (? * INTERVAL '1 day') " +
                     "AND EXISTS (SELECT 1 FROM scan_jobs n WHERE n.repo_id = j.repo_id AND n.id > j.id) " +
                     "ORDER BY j.id LIMIT ?)";
        return jdbcTemplate.update(sql, days, limit);
    }

    public Optional<ScanJob> findLatestByRepoId(Long repoId) {
        String sql = "SELECT * FROM scan_jobs WHERE repo_id = ? ORDER BY created_at DESC, id DESC LIMIT 1";
        return jdbcTemplate.query(sql, scanJobRowMapper, repoId).stream().findFirst();
    }

    public Optional<ScanJob> findById(Long jobId) {
        return jdbcTemplate.query("SELECT * FROM scan_jobs WHERE id = ?", scanJobRowMapper, jobId).stream().findFirst();
    }
}
