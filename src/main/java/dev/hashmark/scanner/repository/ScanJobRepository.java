package dev.hashmark.scanner.repository;

import dev.hashmark.scanner.model.ScanJob;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tarama işleri aynı zamanda kalıcı kuyruktur: PENDING → RUNNING → DONE/FAILED.
 * Çalışan iş {@code heartbeat_at} ile canlılığını bildirir; kalp atışı kesilen iş yeniden kuyruğa alınır.
 *
 * <p><b>Fencing:</b> her sahiplenme yeni bir {@code claim_token} üretir. Jeton alan yazımlar (sonuç, ilerleme,
 * erteleme, geri bırakma) yalnızca jeton hâlâ geçerliyse uygulanır; devredilmiş işin eski çalıştırması yazamaz.
 * Jetonu {@code null} olan çağrılar (eski yollar/testler) jeton denetimi yapmaz.
 */
@Repository
public class ScanJobRepository {

    private static final String TOKEN_MATCHES = " AND (CAST(? AS uuid) IS NULL OR claim_token = CAST(? AS uuid))";

    private final JdbcTemplate jdbcTemplate;

    public ScanJobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static LocalDateTime ts(Timestamp t) {
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
            .triggeredBy(rs.getString("triggered_by"))
            .createdAt(ts(rs.getTimestamp("created_at")))
            .runAfter(ts(rs.getTimestamp("run_after")))
            .deferrals(rs.getInt("deferrals"))
            .claimToken(rs.getObject("claim_token", UUID.class))
            .build();

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_PUSH = "PUSH";

    /** Yeni iş kuyruğa PENDING olarak eklenir. Repo için aktif iş varsa {@code DuplicateKeyException} fırlatır. */
    public ScanJob enqueue(Long repoId, Long userId, String branch) {
        return enqueue(repoId, userId, branch, TRIGGER_MANUAL);
    }

    /** @param triggeredBy {@link #TRIGGER_MANUAL} veya {@link #TRIGGER_PUSH} */
    public ScanJob enqueue(Long repoId, Long userId, String branch, String triggeredBy) {
        String sql = "INSERT INTO scan_jobs (repo_id, user_id, branch, status, triggered_by) " +
                     "VALUES (?, ?, ?, 'PENDING', ?) RETURNING *";
        return jdbcTemplate.queryForObject(sql, scanJobRowMapper, repoId, userId, branch, triggeredBy);
    }

    /** Kuyruk metrikleri ve kuyruk üst sınırı: PENDING sayısı (ertelenenler dahil). */
    public long countPending() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scan_jobs WHERE status = 'PENDING'", Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Kuyruk metrikleri: çalışmaya hazır en eski PENDING işin bekleme süresi (sn); yoksa 0. Ertelenen işin
     * beklemesi erteleme bittiğinde başlar (GitHub kesintisindeki bilinçli erteleme "takılı kuyruk" sayılmaz).
     */
    public double oldestPendingAgeSeconds() {
        Double age = jdbcTemplate.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(COALESCE(run_after, created_at)))), 0) " +
                "FROM scan_jobs WHERE status = 'PENDING' AND (run_after IS NULL OR run_after <= NOW())",
                Double.class);
        return age == null ? 0 : age;
    }

    public long countRunning() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM scan_jobs WHERE status = 'RUNNING'", Long.class);
        return n == null ? 0 : n;
    }

    /** Kullanıcı kotası: kullanıcının kuyrukta bekleyen + çalışan iş sayısı. */
    public int countActiveByUser(Long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scan_jobs WHERE user_id = ? AND status IN ('PENDING', 'RUNNING')",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    /**
     * Kullanıcı kotası: son {@code hours} saatte kullanıcının başlattığı tarama sayısı (kayan pencere; saat dilimi
     * ve gece yarısı patlaması yok). Saklama işi bitmiş işleri 90 günden önce silmediği için sayım eksik kalmaz.
     */
    public int countCreatedByUserWithinHours(Long userId, int hours) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scan_jobs WHERE user_id = ? AND created_at >= NOW() - (? * INTERVAL '1 hour')",
                Integer.class, userId, hours);
        return n == null ? 0 : n;
    }

    /**
     * Bekleyen işleri atomik ve <b>adil</b> sahiplenir (çoklu instance'ta aynı iş iki kez alınmaz).
     * <ul>
     *   <li>Her kullanıcının yalnızca en eski hazır işi adaydır: tek kullanıcı 100 iş kuyruğa koysa da diğer
     *       kullanıcıların işleri arkasında beklemez (round-robin).</li>
     *   <li>Adaylar, kullanıcının şu an çalışan iş sayısına göre sıralanır: çalışan işi olmayan kullanıcı önce.</li>
     *   <li>Ertelenmiş ({@code run_after} gelecekte) işler alınmaz.</li>
     * </ul>
     * Başka bir instance'ın kilitlediği aday atlanır (SKIP LOCKED); bu turda daha az iş alınabilir, kaybolmaz.
     */
    public List<ScanJob> claimPending(int limit) {
        String sql = """
                WITH running AS (
                    SELECT user_id, COUNT(*) AS n FROM scan_jobs WHERE status = 'RUNNING' GROUP BY user_id
                ), head AS (
                    SELECT DISTINCT ON (user_id) id, user_id FROM scan_jobs
                    WHERE status = 'PENDING' AND (run_after IS NULL OR run_after <= NOW())
                    ORDER BY user_id, id
                ), picked AS (
                    SELECT j.id FROM scan_jobs j
                    WHERE j.status = 'PENDING' AND j.id IN (
                        SELECT h.id FROM head h
                        LEFT JOIN running r ON r.user_id IS NOT DISTINCT FROM h.user_id
                        ORDER BY COALESCE(r.n, 0), h.id
                        LIMIT ?)
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE scan_jobs s SET status = 'RUNNING', started_at = NOW(), heartbeat_at = NOW(),
                    attempts = s.attempts + 1, claim_token = gen_random_uuid(), run_after = NULL
                FROM picked WHERE s.id = picked.id
                RETURNING s.*""";
        return jdbcTemplate.query(sql, scanJobRowMapper, limit);
    }

    /** Sahiplenilmiş ama çalıştırılamamış işi kuyruğa geri koyar. */
    public void release(Long jobId) {
        release(jobId, null);
    }

    /**
     * İşi deneme hakkı yakmadan kuyruğa geri koyar (executor reddi, düzgün kapanış). Jeton boşaltılır: geri
     * bırakılan işin eski çalıştırması sonradan sonuç yazamaz.
     *
     * @return iş gerçekten geri bırakıldıysa true
     */
    public boolean release(Long jobId, UUID claimToken) {
        return jdbcTemplate.update("UPDATE scan_jobs SET status = 'PENDING', attempts = GREATEST(attempts - 1, 0), " +
                                   "claim_token = NULL WHERE id = ? AND status = 'RUNNING'" + TOKEN_MATCHES,
                jobId, claimToken, claimToken) == 1;
    }

    /**
     * Geçici hata (GitHub kesintisi, rate-limit, sistem yoğun): işi FAILED yapmak yerine {@code delaySeconds}
     * sonra yeniden çalışmak üzere kuyruğa geri koyar. Deneme hakkı yakılmaz; erteleme sayısı ayrıca sayılır.
     *
     * @return iş gerçekten ertelendiyse true
     */
    public boolean defer(Long jobId, UUID claimToken, long delaySeconds, String message) {
        String sql = "UPDATE scan_jobs SET status = 'PENDING', attempts = GREATEST(attempts - 1, 0), " +
                     "deferrals = deferrals + 1, run_after = NOW() + (? * INTERVAL '1 second'), " +
                     "claim_token = NULL, message = ? WHERE id = ? AND status = 'RUNNING'" + TOKEN_MATCHES;
        return jdbcTemplate.update(sql, delaySeconds, message, jobId, claimToken, claimToken) == 1;
    }

    public void updateProgress(Long jobId, int filesProcessed) {
        updateProgress(jobId, null, filesProcessed);
    }

    public void updateProgress(Long jobId, UUID claimToken, int filesProcessed) {
        String sql = "UPDATE scan_jobs SET files_scanned = ?, heartbeat_at = NOW() WHERE id = ? AND status = 'RUNNING'"
                     + TOKEN_MATCHES;
        jdbcTemplate.update(sql, filesProcessed, jobId, claimToken, claimToken);
    }

    public void heartbeat(Long jobId) {
        jdbcTemplate.update("UPDATE scan_jobs SET heartbeat_at = NOW() WHERE id = ? AND status = 'RUNNING'", jobId);
    }

    public boolean complete(Long jobId, String status, int debtFound, int filesScanned, int filesSkipped,
                            int filesFailed, String commitSha, String message) {
        return complete(jobId, null, status, debtFound, filesScanned, filesSkipped, filesFailed, commitSha, message);
    }

    /**
     * İşi bitirir. Yalnızca hâlâ RUNNING olan ve (jeton verildiyse) aynı sahiplenmeye ait iş kapatılır: kalp
     * atışı kesilip yeniden kuyruğa alınmış/başkasına devredilmiş ya da başka bir çalıştırmayla bitmiş işin
     * sonucunu geç kalan çalıştırma ezemez.
     *
     * @return iş gerçekten kapatıldıysa true
     */
    public boolean complete(Long jobId, UUID claimToken, String status, int debtFound, int filesScanned,
                            int filesSkipped, int filesFailed, String commitSha, String message) {
        String sql = "UPDATE scan_jobs SET status = ?, debt_found = ?, files_scanned = ?, files_skipped = ?, " +
                     "files_failed = ?, commit_sha = ?, message = ?, finished_at = NOW() " +
                     "WHERE id = ? AND status = 'RUNNING'" + TOKEN_MATCHES;
        return jdbcTemplate.update(sql, status, debtFound, filesScanned, filesSkipped, filesFailed, commitSha,
                message, jobId, claimToken, claimToken) == 1;
    }

    /**
     * Kalp atışı kesilen RUNNING işleri yeniden kuyruğa alır; deneme hakkı bitenleri başarısız yapar.
     *
     * @return yeniden kuyruğa alınan + başarısız yapılan iş sayısı
     */
    public int recoverStale(long timeoutMinutes, int maxAttempts) {
        String requeue = "UPDATE scan_jobs SET status = 'PENDING', claim_token = NULL, " +
                         "message = 'Re-queued after interruption' " +
                         "WHERE status = 'RUNNING' AND attempts < ? " +
                         "AND COALESCE(heartbeat_at, started_at, created_at) < NOW() - (? * INTERVAL '1 minute')";
        String fail = "UPDATE scan_jobs SET status = 'FAILED', finished_at = NOW(), claim_token = NULL, " +
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
