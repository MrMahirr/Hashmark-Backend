package dev.hashmark;

import dev.hashmark.auth.repository.OAuthStateRepository;
import dev.hashmark.auth.repository.RefreshTokenRepository;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.util.FingerprintUtil;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.dto.PageResponse;
import dev.hashmark.debt.dto.ReplaceResult;
import dev.hashmark.debt.model.Debt;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.notification.repository.NotificationRepository;
import dev.hashmark.report.dto.TrendDataPoint;
import dev.hashmark.report.repository.ReportRepository;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.job.ScanResultWriter;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.service.GitHubFileService.ScanResult;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.repository.ScanSnapshotRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gerçek PostgreSQL'e karşı çalışan entegrasyon testi. Varsayılan olarak ATLANIR; çalıştırmak için:
 * <pre>
 *   docker run -d --name hashmark-it-pg -e POSTGRES_PASSWORD=it_pass -e POSTGRES_DB=hashmark_it -p 55432:5432 postgres:16-alpine
 *   HASHMARK_TEST_DB_URL=jdbc:postgresql://localhost:55432/hashmark_it \
 *   HASHMARK_TEST_DB_USER=postgres HASHMARK_TEST_DB_PASSWORD=it_pass mvn test -Dtest=DatabaseIntegrationTest
 * </pre>
 * Her test kendi rastgele şemasında çalışır ve sonunda şemayı siler.
 */
@EnabledIfEnvironmentVariable(named = "HASHMARK_TEST_DB_URL", matches = ".+")
class DatabaseIntegrationTest {

    private DriverManagerDataSource adminDataSource;
    private String schema;
    private JdbcTemplate jdbc;
    private DebtRepository debts;
    private ScanJobRepository jobs;
    private ReportRepository reports;
    private ScanSnapshotRepository snapshots;
    private ScanResultWriter writer;

    private long userId;
    private long repoId;

    @BeforeEach
    void setUp() {
        String url = System.getenv("HASHMARK_TEST_DB_URL");
        String user = System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", "");

        schema = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        adminDataSource = new DriverManagerDataSource(url, user, password);
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + schema);

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setSchema(schema);
        jdbc = new JdbcTemplate(dataSource);
        debts = new DebtRepository(jdbc);
        jobs = new ScanJobRepository(jdbc);
        reports = new ReportRepository(jdbc);
        snapshots = new ScanSnapshotRepository(jdbc);
        writer = new ScanResultWriter(new DataSourceTransactionManager(dataSource), jobs, debts,
                new RepoRepository(jdbc), snapshots);
    }

    @AfterEach
    void tearDown() {
        if (schema != null) {
            new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(adminDataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                // application.yml ile aynı: aksi halde CONCURRENTLY migrasyonu Flyway'in kendi transaction'ını bekler
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"));
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private void migrateAll() {
        flyway(null).migrate();
        seedUserAndRepo();
    }

    private void seedUserAndRepo() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_token) VALUES (?, 'a@b.c', 'N', 't') RETURNING id",
                Long.class, "gh-" + UUID.randomUUID());
        repoId = jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name) VALUES (?, ?, 'o/r') RETURNING id",
                Long.class, userId, "r-" + UUID.randomUUID());
    }

    private Debt debt(String file, int line, String label, String content) {
        return debt(file, line, label, content, 0);
    }

    private Debt debt(String file, int line, String label, String content, int occurrence) {
        return Debt.builder()
                .repoId(repoId).filePath(file).lineNo(line).label(label).content(content)
                .fingerprint(FingerprintUtil.fingerprint(label, content)).occurrence(occurrence)
                .build();
    }

    private ReplaceResult scan(String sha, List<Debt> found) {
        return debts.replaceForRepo(repoId, found, sha, null, null);
    }

    private Map<String, Object> row(String file, String label, String content) {
        return jdbc.queryForMap("SELECT * FROM debts WHERE repo_id = ? AND file_path = ? AND fingerprint = ? " +
                        "ORDER BY occurrence LIMIT 1",
                repoId, file, FingerprintUtil.fingerprint(label, content));
    }

    // ------------------------------------------------------------------

    @Test
    void migrationBackfillsLegacyRowsAndKeepsThemMatchable() {
        flyway("6").migrate();
        seedUserAndRepo();
        // Eski şema: UNIQUE(repo_id, file_path, line_no); aynı içerik hem çözülmüş hem açık
        jdbc.update("INSERT INTO debts (repo_id, file_path, line_no, label, content, detected_at) " +
                    "VALUES (?, 'A.java', 9, 'TODO', 'fix  cache', NOW() - INTERVAL '30 days')", repoId);
        jdbc.update("INSERT INTO debts (repo_id, file_path, line_no, label, content, detected_at, resolved_at) " +
                    "VALUES (?, 'A.java', 2, 'TODO', 'fix cache', NOW() - INTERVAL '60 days', NOW() - INTERVAL '50 days')", repoId);
        jdbc.update("INSERT INTO debts (repo_id, file_path, line_no, label, content) VALUES (?, 'A.java', 20, 'FIXME', 'x')", repoId);
        Timestamp originalDetected = jdbc.queryForObject(
                "SELECT detected_at FROM debts WHERE line_no = 9 AND repo_id = ?", Timestamp.class, repoId);

        flyway(null).migrate();

        // SQL md5 formülü ile Java formülü aynı sonucu vermeli
        String expected = FingerprintUtil.fingerprint("TODO", "fix cache");
        List<Map<String, Object>> todos = jdbc.queryForList(
                "SELECT * FROM debts WHERE repo_id = ? AND label = 'TODO' ORDER BY occurrence", repoId);
        assertEquals(2, todos.size());
        assertEquals(expected, todos.get(0).get("fingerprint"));
        assertEquals(0, todos.get(0).get("occurrence"));
        assertNull(todos.get(0).get("resolved_at"), "açık olan 0. sırayı almalı");
        assertEquals(1, todos.get(1).get("occurrence"));

        // Yeni taramada borç kaymış (9 → 7); aynı kayıt korunmalı, FIXME çözülmeli
        ReplaceResult result = scan("a".repeat(40), List.of(debt("A.java", 7, "TODO", "fix cache")));

        Map<String, Object> moved = row("A.java", "TODO", "fix cache");
        assertEquals(7, moved.get("line_no"));
        assertEquals(originalDetected, moved.get("detected_at"), "yaş korunmalı");
        assertEquals(1, result.resolved());
        assertEquals(0, result.added());
        assertEquals(1, result.openTotal());
        assertNotNull(row("A.java", "FIXME", "x").get("resolved_at"));
    }

    @Test
    void movedDebtKeepsIdentityTriageAndAge() {
        migrateAll();
        ReplaceResult first = scan("1".repeat(40), List.of(
                debt("A.java", 10, "TODO", "refactor"), debt("A.java", 30, "FIXME", "crash")));
        assertEquals(2, first.added());
        assertEquals(Map.of("TODO", 1, "FIXME", 1), first.openByLabel());

        long todoId = ((Number) row("A.java", "TODO", "refactor").get("id")).longValue();
        debts.updateTriage(userId, todoId, "IGNORED", "mahir");
        Timestamp detected = (Timestamp) row("A.java", "TODO", "refactor").get("detected_at");

        // Dosyanın başına 5 satır eklendi: tüm satır numaraları kaydı
        ReplaceResult second = scan("2".repeat(40), List.of(
                debt("A.java", 15, "TODO", "refactor"), debt("A.java", 35, "FIXME", "crash")));

        Map<String, Object> todo = row("A.java", "TODO", "refactor");
        assertEquals(todoId, ((Number) todo.get("id")).longValue(), "id değişmemeli");
        assertEquals(15, todo.get("line_no"));
        assertEquals("IGNORED", todo.get("triage_status"), "triyaj taramada korunmalı");
        assertEquals("mahir", todo.get("assigned_to"));
        assertEquals(detected, todo.get("detected_at"));
        assertEquals("2".repeat(40), todo.get("last_seen_sha"));
        assertEquals(0, second.added());
        assertEquals(0, second.resolved());
        assertEquals(1, second.openTotal(), "yok sayılan borç aktif toplamda sayılmaz");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM debts WHERE repo_id = ?", Integer.class, repoId));
    }

    @Test
    void identicalCommentsInOneFileAreTrackedSeparately() {
        migrateAll();
        scan("a".repeat(40), List.of(
                debt("A.java", 3, "TODO", "same", 0), debt("A.java", 8, "TODO", "same", 1), debt("A.java", 13, "TODO", "same", 2)));

        // Ortadaki silindi → son iki kayıt sıra 0 ve 1'e düşer, 3. kayıt çözülür
        ReplaceResult result = scan("b".repeat(40), List.of(
                debt("A.java", 3, "TODO", "same", 0), debt("A.java", 12, "TODO", "same", 1)));

        assertEquals(1, result.resolved());
        assertEquals(2, result.openTotal());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM debts WHERE repo_id = ? AND resolved_at IS NOT NULL", Integer.class, repoId));
    }

    @Test
    void resolvedDebtThatReturnsGetsFreshAgeAndBlame() {
        migrateAll();
        scan("a".repeat(40), List.of(debt("A.java", 1, "TODO", "x")));
        long id = ((Number) row("A.java", "TODO", "x").get("id")).longValue();
        debts.updateBlame(id, "mahir", "Mahir", LocalDateTime.of(2024, 1, 1, 0, 0), "c".repeat(40));
        jdbc.update("UPDATE debts SET detected_at = NOW() - INTERVAL '90 days' WHERE id = ?", id);

        scan("b".repeat(40), List.of());                            // çözüldü
        assertNotNull(row("A.java", "TODO", "x").get("resolved_at"));
        ReplaceResult back = scan("c".repeat(40), List.of(debt("A.java", 4, "TODO", "x"))); // geri geldi

        Map<String, Object> reopened = row("A.java", "TODO", "x");
        assertNull(reopened.get("resolved_at"));
        assertNull(reopened.get("author_login"), "blame sıfırlanmalı");
        assertNull(reopened.get("blame_at"));
        assertTrue(((Timestamp) reopened.get("detected_at")).toLocalDateTime().isAfter(LocalDateTime.now().minusDays(1)),
                "yaş yeniden başlamalı");
        assertEquals(1, back.added());
    }

    @Test
    void partialScanOnlyResolvesScannedFilesAndKeepOpenPathsAreProtected() {
        migrateAll();
        scan("a".repeat(40), List.of(
                debt("A.java", 1, "TODO", "a"), debt("B.java", 1, "TODO", "b"), debt("C.java", 1, "TODO", "c")));

        // Kısmi tarama: yalnızca A.java tarandı ve borç kalmadı → B ve C dokunulmaz
        debts.replaceForRepo(repoId, List.of(), "b".repeat(40), Set.of("A.java"), Set.of());
        assertNotNull(row("A.java", "TODO", "a").get("resolved_at"));
        assertNull(row("B.java", "TODO", "b").get("resolved_at"));
        assertNull(row("C.java", "TODO", "c").get("resolved_at"));

        // Tam tarama, ama C.java okunamadı → B çözülür, C korunur
        debts.replaceForRepo(repoId, List.of(), "c".repeat(40), null, Set.of("C.java"));
        assertNotNull(row("B.java", "TODO", "b").get("resolved_at"));
        assertNull(row("C.java", "TODO", "c").get("resolved_at"));
    }

    @Test
    void filterSearchSortAndClamping() {
        migrateAll();
        scan("a".repeat(40), List.of(
                debt("src/Alpha.java", 5, "TODO", "100% done_maybe"),
                debt("src/Beta.java", 2, "FIXME", "plain"),
                debt("lib/Gamma.java", 9, "TODO", "other")));

        // % ve _ joker değil, düz karakter
        DebtFilterRequest q = DebtFilterRequest.builder().q("100%").build();
        assertEquals(1, debts.findByFilter(userId, q).getTotalElements());
        q.setQ("done_maybe");
        assertEquals(1, debts.findByFilter(userId, q).getTotalElements());
        q.setQ("%");
        assertEquals(1, debts.findByFilter(userId, q).getTotalElements(), "yalnızca gerçek % içeren");
        q.setQ("src/");
        assertEquals(2, debts.findByFilter(userId, q).getTotalElements());

        // sıralama
        DebtFilterRequest sorted = DebtFilterRequest.builder().sort("filePath").dir("asc").build();
        List<DebtDto> list = debts.findByFilter(userId, sorted).getContent();
        assertEquals("lib/Gamma.java", list.get(0).getFilePath());
        sorted.setSort("lineNo");
        sorted.setDir("desc");
        assertEquals(9, debts.findByFilter(userId, sorted).getContent().get(0).getLineNo());

        // enjeksiyon denemesi
        sorted.setSort("id; DROP TABLE debts; --");
        assertEquals(3, debts.findByFilter(userId, sorted).getContent().size());

        // sayfa boyutu üst sınırı
        PageResponse<DebtDto> page = debts.findByFilter(userId, DebtFilterRequest.builder().size(100000).build());
        assertEquals(DebtFilterRequest.MAX_PAGE_SIZE, page.getSize());

        // durum filtresi
        long id = list.get(0).getId();
        debts.updateTriage(userId, id, "WONT_FIX", null);
        assertEquals(1, debts.findByFilter(userId, DebtFilterRequest.builder().status("ignored").build()).getTotalElements());
        assertEquals(2, debts.findByFilter(userId, DebtFilterRequest.builder().status("open").build()).getTotalElements());
        assertEquals(2, debts.getStats(userId, null).getTotal());
        assertEquals(3, debts.findForExport(userId, new DebtFilterRequest(), 100).size());
    }

    @Test
    void triageIsScopedToOwnerAndPermalinkIsBuilt() {
        migrateAll();
        scan("d".repeat(40), List.of(debt("src/My File.java", 4, "TODO", "x")));
        DebtDto dto = debts.findByFilter(userId, new DebtFilterRequest()).getContent().get(0);

        assertEquals("https://github.com/o/r/blob/" + "d".repeat(40) + "/src/My%20File.java#L4", dto.getPermalink());
        assertTrue(debts.updateTriage(userId + 999, dto.getId(), "IGNORED", null).isEmpty(), "başkası değiştirememeli");
        assertEquals("OPEN", debts.findByIdForUser(userId, dto.getId()).orElseThrow().getTriageStatus());

        DebtDto assigned = debts.updateTriage(userId, dto.getId(), null, "ayse").orElseThrow();
        assertEquals("ayse", assigned.getAssignee());
        DebtDto cleared = debts.updateTriage(userId, dto.getId(), null, "").orElseThrow();
        assertNull(cleared.getAssignee());
    }

    @Test
    void parserMetadataIsPersistedAndManualAssigneeWins() {
        migrateAll();
        Debt d = debt("A.java", 1, "TODO", "x");
        d.setParsedAssignee("kod");
        d.setPriority("HIGH");
        d.setDueDate(LocalDate.of(2026, 12, 1));
        d.setIssueRef("#12");
        scan("a".repeat(40), List.of(d));

        DebtDto dto = debts.findByFilter(userId, new DebtFilterRequest()).getContent().get(0);
        assertEquals("kod", dto.getAssignee());
        assertEquals("HIGH", dto.getPriority());
        assertEquals(LocalDate.of(2026, 12, 1), dto.getDueDate());
        assertEquals("#12", dto.getIssueRef());

        debts.updateTriage(userId, dto.getId(), null, "elle");
        d.setParsedAssignee("baska");
        scan("b".repeat(40), List.of(d));
        assertEquals("elle", debts.findByIdForUser(userId, dto.getId()).orElseThrow().getAssignee());
    }

    @Test
    void blameQueueHelpers() {
        migrateAll();
        scan("a".repeat(40), List.of(
                debt("A.java", 3, "TODO", "x"), debt("A.java", 8, "NOTE", "n"), debt("B.java", 1, "FIXME", "y")));

        assertEquals(Set.of("A.java", "B.java"), Set.copyOf(debts.findFilesNeedingBlame(repoId, 10)));
        assertEquals(1, debts.findFilesNeedingBlame(repoId, 1).size());
        List<DebtRepository.LineRef> lines = debts.findLinesNeedingBlame(repoId, "A.java");
        assertEquals(1, lines.size(), "NOTE etiketi blame sorgulamaz");
        assertEquals(3, lines.get(0).lineNo());

        debts.updateBlame(lines.get(0).id(), "mahir", "Mahir", LocalDateTime.of(2025, 5, 5, 5, 5), "e".repeat(40));
        assertEquals(List.of("B.java"), debts.findFilesNeedingBlame(repoId, 10));
        DebtDto dto = debts.findByIdForUser(userId, lines.get(0).id()).orElseThrow();
        assertEquals("mahir", dto.getAuthorLogin());
        assertEquals(LocalDateTime.of(2025, 5, 5, 5, 5), dto.getIntroducedAt());
    }

    @Test
    void queueLifecycleClaimHeartbeatRecoveryAndGuard() {
        migrateAll();

        assertEquals(0, jobs.countPending());
        assertEquals(0.0, jobs.oldestPendingAgeSeconds(), "boş kuyrukta yaş 0");

        ScanJob queued = jobs.enqueue(repoId, userId, "dev");
        assertEquals("PENDING", queued.getStatus());
        assertEquals(1, jobs.countPending(), "kuyruk metriği: bekleyen sayısı");
        assertTrue(jobs.oldestPendingAgeSeconds() >= 0.0);
        assertEquals("dev", queued.getBranch());
        assertThrows(DuplicateKeyException.class, () -> jobs.enqueue(repoId, userId, null),
                "repo başına tek aktif iş");

        List<ScanJob> claimed = jobs.claimPending(5);
        assertEquals(1, claimed.size());
        assertEquals("RUNNING", claimed.get(0).getStatus());
        assertEquals(1, jobs.countRunning(), "kuyruk metriği: çalışan sayısı");
        assertEquals(0, jobs.countPending());
        assertEquals(1, claimed.get(0).getAttempts());
        assertTrue(jobs.claimPending(5).isEmpty(), "aynı iş iki kez alınmamalı");
        assertThrows(DuplicateKeyException.class, () -> jobs.enqueue(repoId, userId, null), "RUNNING de aktif sayılır");

        // kalp atışı taze → kurtarılmaz
        assertEquals(0, jobs.recoverStale(5, 3));

        // kalp atışı eski → yeniden kuyruğa
        jdbc.update("UPDATE scan_jobs SET heartbeat_at = NOW() - INTERVAL '10 minutes' WHERE id = ?", queued.getId());
        assertEquals(1, jobs.recoverStale(5, 3));
        assertEquals("PENDING", jobs.findById(queued.getId()).orElseThrow().getStatus());

        // tekrar alınır, deneme hakkı bitince FAILED
        jobs.claimPending(1);
        jdbc.update("UPDATE scan_jobs SET heartbeat_at = NOW() - INTERVAL '10 minutes', attempts = 3 WHERE id = ?", queued.getId());
        assertEquals(1, jobs.recoverStale(5, 3));
        ScanJob failed = jobs.findById(queued.getId()).orElseThrow();
        assertEquals("FAILED", failed.getStatus());
        assertNotNull(failed.getFinishedAt());

        // tamamlanınca yeni iş eklenebilir
        ScanJob next = jobs.enqueue(repoId, userId, null);
        jobs.claimPending(1);
        jobs.complete(next.getId(), "DONE", 4, 10, 1, 0, "f".repeat(40), null);
        ScanJob done = jobs.findById(next.getId()).orElseThrow();
        assertEquals("DONE", done.getStatus());
        assertEquals("f".repeat(40), done.getCommitSha());
        assertEquals(10, done.getFilesScanned());
        jobs.enqueue(repoId, userId, null);
    }

    private ScanResult result(String sha) {
        return new ScanResult(1, 0, 0, Set.of(), Set.of(), false, null, sha);
    }

    @Test
    void scanResultIsWrittenAtomically() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        jobs.claimPending(1);

        var replaced = writer.write(job.getId(), repoId, List.of(debt("A.java", 1, "TODO", "x")),
                result("a".repeat(40)), null);

        assertTrue(replaced.isPresent());
        assertEquals("DONE", jobs.findById(job.getId()).orElseThrow().getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM debts WHERE repo_id = ?", Integer.class, repoId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM scan_snapshots WHERE scan_job_id = ?",
                Integer.class, job.getId()));
        assertEquals("a".repeat(40), jdbc.queryForObject("SELECT last_scanned_sha FROM repos WHERE id = ?",
                String.class, repoId));
    }

    @Test
    void scanResultWriteIsSkippedWhenJobIsNoLongerRunning() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        jobs.claimPending(1);
        assertTrue(writer.write(job.getId(), repoId, List.of(debt("A.java", 1, "TODO", "x")),
                result("a".repeat(40)), null).isPresent());

        // geç kalan ikinci çalıştırma: iş zaten DONE → hiçbir şey yazılmaz, snapshot çoğalmaz
        var second = writer.write(job.getId(), repoId, List.of(debt("B.java", 1, "FIXME", "y")),
                result("b".repeat(40)), null);

        assertTrue(second.isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM debts WHERE repo_id = ?", Integer.class, repoId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM scan_snapshots WHERE scan_job_id = ?",
                Integer.class, job.getId()));
        assertEquals("a".repeat(40), jdbc.queryForObject("SELECT commit_sha FROM scan_jobs WHERE id = ?",
                String.class, job.getId()));
    }

    @Test
    void scanResultRollsBackEverythingWhenALaterStepFails() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        jobs.claimPending(1);
        // snapshot adımını düşürmek için tabloyu kaldır
        jdbc.execute("DROP TABLE scan_snapshots CASCADE");

        assertThrows(RuntimeException.class, () -> writer.write(job.getId(), repoId,
                List.of(debt("A.java", 1, "TODO", "x")), result("a".repeat(40)), null));

        assertEquals("RUNNING", jobs.findById(job.getId()).orElseThrow().getStatus(), "iş kapatılmamalı");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM debts WHERE repo_id = ?", Integer.class, repoId),
                "borçlar geri alınmalı");
    }

    @Test
    void snapshotSaveIsIdempotentPerJob() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        snapshots.save(repoId, job.getId(), null, 3, Map.of("TODO", 3), 3, 0);
        snapshots.save(repoId, job.getId(), null, 5, Map.of("TODO", 5), 2, 0);

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM scan_snapshots WHERE scan_job_id = ?",
                Integer.class, job.getId()));
        assertEquals(3, jdbc.queryForObject("SELECT total_open FROM scan_snapshots WHERE scan_job_id = ?",
                Integer.class, job.getId()), "ilk yazım korunur");
    }

    @Test
    void completeOnlyClosesRunningJobs() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        assertFalse(jobs.complete(job.getId(), "DONE", 0, 0, 0, 0, null, null), "PENDING iş kapatılamaz");
        jobs.claimPending(1);
        assertTrue(jobs.complete(job.getId(), "DONE", 0, 0, 0, 0, null, null));
        assertFalse(jobs.complete(job.getId(), "FAILED", 0, 0, 0, 0, null, "late"), "ikinci kapatma yok sayılır");
        assertEquals("DONE", jobs.findById(job.getId()).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // Veri yaşam döngüsü (retention)
    // ------------------------------------------------------------------

    private long insertJob(long repo, String status, String finishedAgo) {
        return jdbc.queryForObject(
                "INSERT INTO scan_jobs (repo_id, user_id, status, created_at, finished_at) " +
                "VALUES (?, ?, ?, NOW() - CAST(? AS INTERVAL), NOW() - CAST(? AS INTERVAL)) RETURNING id",
                Long.class, repo, userId, status, finishedAgo, finishedAgo);
    }

    @Test
    void retentionDeletesOldFinishedJobsButKeepsLatestPerRepo() {
        migrateAll();
        long old1 = insertJob(repoId, "DONE", "200 days");
        long old2 = insertJob(repoId, "FAILED", "150 days");
        long recent = insertJob(repoId, "DONE", "10 days");
        long otherRepo = jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name) VALUES (?, ?, 'o/x') RETURNING id",
                Long.class, userId, "r-" + UUID.randomUUID());
        long onlyJobOfOther = insertJob(otherRepo, "DONE", "300 days");
        snapshots.save(repoId, old1, null, 1, Map.of(), 1, 0);

        assertEquals(1, jobs.deleteFinishedOlderThan(90, 1), "parti sınırına uyar");
        assertEquals(1, jobs.deleteFinishedOlderThan(90, 100));
        assertEquals(0, jobs.deleteFinishedOlderThan(90, 100), "idempotent");

        assertTrue(jobs.findById(old1).isEmpty());
        assertTrue(jobs.findById(old2).isEmpty());
        assertTrue(jobs.findById(recent).isPresent());
        assertTrue(jobs.findById(onlyJobOfOther).isPresent(), "deponun son işi tarama durumu için korunur");
        assertNull(jdbc.queryForObject("SELECT scan_job_id FROM scan_snapshots WHERE repo_id = ?", Long.class, repoId),
                "snapshot kalır, iş bağlantısı boşalır");
    }

    @Test
    void retentionNeverDeletesActiveJobs() {
        migrateAll();
        insertJob(repoId, "DONE", "200 days");
        jdbc.update("INSERT INTO scan_jobs (repo_id, user_id, status, created_at) " +
                    "VALUES (?, ?, 'PENDING', NOW() - INTERVAL '400 days')", repoId, userId);
        assertEquals(1, jobs.deleteFinishedOlderThan(90, 100));
        assertEquals(1, jobs.countPending());
    }

    private void insertSnapshot(String takenAt, int totalOpen, int added, int resolved) {
        jdbc.update("INSERT INTO scan_snapshots (repo_id, taken_at, total_open, todo_count, added, resolved) " +
                    "VALUES (?, CAST(? AS TIMESTAMP), ?, ?, ?, ?)", repoId, takenAt, totalOpen, totalOpen, added, resolved);
    }

    @Test
    void retentionCompactsOldSnapshotsToWeeklyWithoutChangingTrend() {
        migrateAll();
        // 200 gün önceki haftanın salı/çarşamba/perşembesi + bir sonraki hafta + yakın tarih
        LocalDate monday = LocalDate.now().minusDays(200).with(java.time.DayOfWeek.MONDAY);
        insertSnapshot(monday.plusDays(1) + " 10:00", 5, 5, 0);
        insertSnapshot(monday.plusDays(2) + " 10:00", 6, 2, 1);
        insertSnapshot(monday.plusDays(3) + " 10:00", 7, 3, 2);
        insertSnapshot(monday.plusDays(8) + " 10:00", 4, 0, 3);
        insertSnapshot(LocalDate.now().minusDays(1) + " 10:00", 9, 5, 0);
        LocalDate trendDay = monday.plusWeeks(3);
        List<TrendDataPoint> before = reports.getTrendFromSnapshots(userId, null, trendDay);

        assertEquals(2, snapshots.compactToWeekly(90));
        assertEquals(0, snapshots.compactToWeekly(90), "idempotent");

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM scan_snapshots", Integer.class));
        Map<String, Object> week = jdbc.queryForMap(
                "SELECT total_open, added, resolved FROM scan_snapshots WHERE taken_at < ?",
                Timestamp.valueOf(monday.plusDays(7).atStartOfDay()));
        assertEquals(7, week.get("total_open"), "haftanın son değeri");
        assertEquals(10, week.get("added"), "haftanın toplamı");
        assertEquals(3, week.get("resolved"));
        assertEquals(before, reports.getTrendFromSnapshots(userId, null, trendDay), "trend değişmemeli");
    }

    @Test
    void retentionDeletesSnapshotsOlderThanLimit() {
        migrateAll();
        insertSnapshot(LocalDate.now().minusMonths(25) + " 10:00", 1, 1, 0);
        insertSnapshot(LocalDate.now().minusMonths(23) + " 10:00", 2, 1, 0);
        assertEquals(1, snapshots.deleteOlderThanMonths(24, 100));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM scan_snapshots", Integer.class));
    }

    @Test
    void retentionDeletesOnlyOldReadNotifications() {
        migrateAll();
        String sql = "INSERT INTO notifications (user_id, title, description, type, is_read, created_at) " +
                     "VALUES (?, 't', 'd', 'INFO', ?, NOW() - CAST(? AS INTERVAL))";
        jdbc.update(sql, userId, true, "200 days");   // silinir
        jdbc.update(sql, userId, false, "400 days");  // okunmamış: kalır
        jdbc.update(sql, userId, true, "10 days");    // yeni: kalır
        NotificationRepository notifications = new NotificationRepository(jdbc);

        assertEquals(1, notifications.deleteReadOlderThan(180, 100));
        assertEquals(0, notifications.deleteReadOlderThan(180, 100), "idempotent");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Integer.class));
    }

    @Test
    void releaseReturnsClaimedJobToQueue() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        jobs.claimPending(1);

        jobs.release(job.getId());

        ScanJob again = jobs.findById(job.getId()).orElseThrow();
        assertEquals("PENDING", again.getStatus());
        assertEquals(0, again.getAttempts());
    }

    @Test
    void trendIsBuiltFromSnapshotsWithCarryForward() {
        migrateAll();
        long secondRepo = jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name) VALUES (?, 'r2', 'o/r2') RETURNING id",
                Long.class, userId);

        LocalDate monday = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        // repo 1: 3 hafta önce 10 açık, 1 hafta önce 7 açık
        insertSnapshot(repoId, monday.minusWeeks(3).plusDays(1), 10, 10, 0);
        insertSnapshot(repoId, monday.minusWeeks(1).plusDays(2), 7, 1, 4);
        // repo 2: 2 hafta önce 5 açık
        insertSnapshot(secondRepo, monday.minusWeeks(2).plusDays(3), 5, 5, 0);

        List<TrendDataPoint> trend = reports.getTrendFromSnapshots(userId, null, LocalDate.now());

        // başlangıç = ilk görüntü haftası (3 hafta önce), bugüne kadar 4 nokta
        assertEquals(4, trend.size());
        assertEquals(monday.minusWeeks(3), trend.get(0).getWeekStart());
        assertEquals(10, trend.get(0).getTotalDebts());
        assertEquals(10, trend.get(0).getNewDebts());
        assertEquals(15, trend.get(1).getTotalDebts(), "repo 2 eklendi, repo 1 değeri taşındı");
        assertEquals(12, trend.get(2).getTotalDebts(), "repo 1: 7, repo 2: 5");
        assertEquals(4, trend.get(2).getResolvedDebts());
        assertEquals(12, trend.get(3).getTotalDebts(), "snapshot olmayan hafta: değer taşınır");
        assertEquals(0, trend.get(3).getNewDebts());

        // tek repo
        List<TrendDataPoint> single = reports.getTrendFromSnapshots(userId, secondRepo, LocalDate.now());
        assertEquals(5, single.get(single.size() - 1).getTotalDebts());

        // getTrendData: görüntü varsa onu kullanır
        assertEquals(4, reports.getTrendData(userId, null).size());
    }

    @Test
    void snapshotRepositoryStoresLabelCounts() {
        migrateAll();

        snapshots.save(repoId, null, "a".repeat(40), 9, Map.of("TODO", 4, "NOTE", 5), 2, 1);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM scan_snapshots WHERE repo_id = ?", repoId);
        assertEquals(9, row.get("total_open"));
        assertEquals(4, row.get("todo_count"));
        assertEquals(5, row.get("note_count"));
        assertEquals(0, row.get("fixme_count"));
        assertEquals(2, row.get("added"));
    }

    @Test
    void labelStatsAndModulesExcludeIgnoredDebts() {
        migrateAll();
        scan("a".repeat(40), List.of(debt("src/A.java", 1, "TODO", "a"), debt("lib/B.java", 1, "TODO", "b")));
        long id = ((Number) row("lib/B.java", "TODO", "b").get("id")).longValue();
        debts.updateTriage(userId, id, "IGNORED", null);

        assertEquals(1, reports.getLabelStats(userId, null).getTodoCount());
        assertEquals(1, reports.getTopModules(userId, null).size());
        assertFalse(reports.getTopModules(userId, null).stream().anyMatch(m -> "lib".equals(m.getModulePath())));
    }

    @Test
    void manuallyResolvedDebtIsReopenedWhenCodeIsStillThere() {
        migrateAll();
        scan("a".repeat(40), List.of(debt("A.java", 3, "TODO", "still here")));
        long id = ((Number) row("A.java", "TODO", "still here").get("id")).longValue();

        debts.updateTriage(userId, id, "RESOLVED", null);
        Map<String, Object> resolved = row("A.java", "TODO", "still here");
        assertNotNull(resolved.get("resolved_at"));
        assertEquals(true, resolved.get("manually_resolved"));
        assertEquals("OPEN", resolved.get("triage_status"));

        // Kod hâlâ duruyor: sonraki tarama yeniden açar ve bunu bildirir
        ReplaceResult again = scan("b".repeat(40), List.of(debt("A.java", 3, "TODO", "still here")));
        Map<String, Object> reopened = row("A.java", "TODO", "still here");
        assertNull(reopened.get("resolved_at"));
        assertEquals(false, reopened.get("manually_resolved"));
        assertEquals(1, again.reopened());
    }

    @Test
    void manuallyResolvedDebtStaysResolvedWhenCodeIsGone() {
        migrateAll();
        scan("a".repeat(40), List.of(debt("A.java", 3, "TODO", "temp")));
        long id = ((Number) row("A.java", "TODO", "temp").get("id")).longValue();
        debts.updateTriage(userId, id, "RESOLVED", null);

        ReplaceResult next = scan("b".repeat(40), List.of());
        assertNotNull(row("A.java", "TODO", "temp").get("resolved_at"));
        assertEquals(0, next.reopened());
    }

    @Test
    void reopenClearsManualResolveButNotScanResolve() {
        migrateAll();
        scan("a".repeat(40), List.of(debt("A.java", 1, "TODO", "manual"), debt("B.java", 1, "TODO", "gone")));
        long manual = ((Number) row("A.java", "TODO", "manual").get("id")).longValue();
        long gone = ((Number) row("B.java", "TODO", "gone").get("id")).longValue();

        debts.updateTriage(userId, manual, "RESOLVED", null);
        scan("b".repeat(40), List.of(debt("A.java", 1, "TODO", "manual"))); // gone çözülür, manual yeniden açılır
        debts.updateTriage(userId, manual, "RESOLVED", null);

        debts.updateTriage(userId, manual, "OPEN", null);
        debts.updateTriage(userId, gone, "OPEN", null);

        assertNull(row("A.java", "TODO", "manual").get("resolved_at"), "elle çözülen geri açılmalı");
        assertNotNull(row("B.java", "TODO", "gone").get("resolved_at"), "tarama çözümü korunmalı");
    }

    @Test
    void repoTrackingFlagAndBreakdown() {
        migrateAll();
        var repos = new dev.hashmark.repo.repository.RepoRepository(jdbc);

        // Mevcut (test tohumu) depo izleniyor sayılır
        assertEquals(1, repos.findAllByUserIdWithDebtCount(userId).size());

        // Senkronla gelen yeni depo aday olur
        var candidate = repos.save(dev.hashmark.repo.model.Repo.builder()
                .userId(userId).githubRepoId("gh-new").fullName("o/new").isPrivate(false).build());
        assertEquals(false, candidate.getTracked());
        assertEquals(1, repos.findCandidatesByUserId(userId).size());
        assertEquals(1, repos.findAllByUserIdWithDebtCount(userId).size());

        assertEquals(1, repos.markTracked(candidate.getId(), userId));
        assertEquals(2, repos.findAllByUserIdWithDebtCount(userId).size());
        assertEquals(0, repos.markTracked(candidate.getId(), userId + 999), "başkasının deposu izlenemez");

        // Yeniden senkron mevcut tracked değerini bozmaz
        repos.save(dev.hashmark.repo.model.Repo.builder()
                .userId(userId).githubRepoId("gh-new").fullName("o/new").isPrivate(false).build());
        assertEquals(0, repos.findCandidatesByUserId(userId).size());

        scan("a".repeat(40), List.of(debt("A.java", 1, "TODO", "a"), debt("A.java", 2, "FIXME", "b"),
                debt("A.java", 3, "TODO", "c")));
        Map<String, Integer> breakdown = repos.findOpenBreakdownByUserId(userId).get(repoId);
        assertEquals(2, breakdown.get("TODO"));
        assertEquals(1, breakdown.get("FIXME"));
    }

    @Test
    void digestSettingsRoundTripAndRecipients() {
        migrateAll();
        var settings = new dev.hashmark.settings.repository.UserSettingsRepository(jdbc);
        var saved = settings.save(dev.hashmark.settings.model.UserSettings.builder()
                .userId(userId).emailNotify(true).notifyDay("FRIDAY")
                .digestLabels("TODO,FIXME").digestEmail("team@example.com").build());
        assertEquals("TODO,FIXME", saved.getDigestLabels());
        assertEquals("team@example.com", saved.getDigestEmail());

        LocalDate friday = LocalDate.of(2026, 10, 16);
        assertEquals(1, settings.findDigestRecipients("FRIDAY", friday).size());
        assertEquals(0, settings.findDigestRecipients("MONDAY", friday).size());

        settings.markDigestSent(userId, friday);
        assertEquals(0, settings.findDigestRecipients("FRIDAY", friday).size(), "aynı gün ikinci kez gönderilmez");
        assertEquals(1, settings.findDigestRecipients("FRIDAY", friday.plusWeeks(1)).size());
    }

    @Test
    void refreshTokenIsSingleUseAndReuseIsDetected() {
        migrateAll();
        RefreshTokenRepository tokens = new RefreshTokenRepository(jdbc);
        tokens.insert("j1", "fam", userId, 60_000);

        assertTrue(tokens.markUsedIfActive("j1"));
        assertFalse(tokens.markUsedIfActive("j1"), "ikinci kullanım reddedilmeli");

        var state = tokens.findState("j1").orElseThrow();
        assertTrue(state.used());
        assertFalse(state.revoked());
        assertEquals("fam", state.familyId());

        tokens.insert("j2", "fam", userId, 60_000);
        assertEquals(2, tokens.revokeFamily("fam"));
        assertFalse(tokens.markUsedIfActive("j2"), "iptal edilen aile kullanılamaz");
        assertTrue(tokens.findState("nope").isEmpty());
    }

    @Test
    void expiredRefreshTokenCannotBeUsed() {
        migrateAll();
        RefreshTokenRepository tokens = new RefreshTokenRepository(jdbc);
        tokens.insert("old", "fam", userId, -1000);
        assertFalse(tokens.markUsedIfActive("old"));
    }

    @Test
    void oauthStateIsConsumedOnce() {
        migrateAll();
        OAuthStateRepository states = new OAuthStateRepository(jdbc);
        states.insert("s1", 60_000);
        states.insert("s2", -1000);
        assertTrue(states.consume("s1"));
        assertFalse(states.consume("s1"), "state tek kullanımlık");
        assertFalse(states.consume("s2"), "süresi dolan state reddedilir");
        assertFalse(states.consume("unknown"));
        assertEquals(1, states.deleteExpired());
    }

    @Test
    void deletingUserRemovesAllTheirData() {
        migrateAll();
        RefreshTokenRepository tokens = new RefreshTokenRepository(jdbc);
        tokens.insert("j1", "fam", userId, 60_000);
        jdbc.update("INSERT INTO debts (repo_id, file_path, line_no, label, content, fingerprint) VALUES (?, 'A.java', 1, 'TODO', 'x', 'fp')", repoId);

        assertEquals(1, new UserRepository(jdbc).deleteById(userId));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM repos WHERE user_id = ?", Integer.class, userId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM debts", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class));
    }

    // ------------------------------------------------------------------
    // Faz 3: adil kuyruk, erteleme, fencing, kotalar, dağıtık kilit
    // ------------------------------------------------------------------

    private long newUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_token) VALUES (?, 'x@y.z', 'N', 't') RETURNING id",
                Long.class, "gh-" + UUID.randomUUID());
    }

    private long newRepo(long owner) {
        return jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name) VALUES (?, ?, 'o/r') RETURNING id",
                Long.class, owner, "r-" + UUID.randomUUID());
    }

    @Test
    void claimIsFairAcrossUsers() {
        migrateAll();
        long other = newUser();
        // A kullanıcısı önce 3 iş kuyruğa koyar, B sonra 1 iş
        ScanJob a1 = jobs.enqueue(repoId, userId, null);
        ScanJob a2 = jobs.enqueue(newRepo(userId), userId, null);
        jobs.enqueue(newRepo(userId), userId, null);
        ScanJob b1 = jobs.enqueue(newRepo(other), other, null);

        List<ScanJob> claimed = jobs.claimPending(2);

        assertEquals(Set.of(a1.getId(), b1.getId()), Set.of(claimed.get(0).getId(), claimed.get(1).getId()),
                "B'nin işi A'nın kuyruğunun arkasında beklememeli");
        claimed.forEach(j -> assertNotNull(j.getClaimToken(), "her sahiplenme jeton alır"));

        // İki kullanıcının da 1 çalışan işi var: eşitlikte en eski baş iş (a2) önce, kullanıcı başına bir aday
        jobs.enqueue(newRepo(other), other, null);
        List<ScanJob> next = jobs.claimPending(1);
        assertEquals(List.of(a2.getId()), next.stream().map(ScanJob::getId).toList());
    }

    @Test
    void claimPrefersUsersWithFewerRunningJobs() {
        migrateAll();
        long other = newUser();
        jobs.enqueue(repoId, userId, null);
        jobs.claimPending(1);                                   // A'nın 1 işi çalışıyor
        ScanJob a2 = jobs.enqueue(newRepo(userId), userId, null);
        ScanJob b1 = jobs.enqueue(newRepo(other), other, null); // B daha yeni ama hiç çalışan işi yok

        List<ScanJob> claimed = jobs.claimPending(1);

        assertEquals(b1.getId(), claimed.get(0).getId());
        assertEquals("PENDING", jobs.findById(a2.getId()).orElseThrow().getStatus());
    }

    @Test
    void deferredJobWaitsUntilRunAfterAndKeepsAttempts() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        ScanJob claimed = jobs.claimPending(1).get(0);

        assertTrue(jobs.defer(job.getId(), claimed.getClaimToken(), 3600, "Retrying in 3600s: GitHub down"));

        ScanJob deferred = jobs.findById(job.getId()).orElseThrow();
        assertEquals("PENDING", deferred.getStatus());
        assertEquals(0, deferred.getAttempts(), "erteleme deneme hakkı yakmaz");
        assertEquals(1, deferred.getDeferrals());
        assertNull(deferred.getClaimToken());
        assertTrue(jobs.claimPending(5).isEmpty(), "erteleme bitmeden alınmaz");
        assertEquals(1, jobs.countPending(), "kuyruk üst sınırına sayılır");
        assertEquals(0.0, jobs.oldestPendingAgeSeconds(), "bilinçli erteleme takılı kuyruk sayılmaz");

        jdbc.update("UPDATE scan_jobs SET run_after = NOW() - INTERVAL '1 second' WHERE id = ?", job.getId());
        ScanJob again = jobs.claimPending(5).get(0);
        assertEquals(job.getId(), again.getId());
        assertNull(again.getRunAfter());
        assertFalse(claimed.getClaimToken().equals(again.getClaimToken()), "yeni sahiplenme yeni jeton");
    }

    @Test
    void staleRunIsFencedOutAfterHandover() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        java.util.UUID oldToken = jobs.claimPending(1).get(0).getClaimToken();

        // Kalp atışı kesildi → kurtarıldı → başka bir instance sahiplendi
        jdbc.update("UPDATE scan_jobs SET heartbeat_at = NOW() - INTERVAL '10 minutes' WHERE id = ?", job.getId());
        jobs.recoverStale(5, 3);
        java.util.UUID newToken = jobs.claimPending(1).get(0).getClaimToken();

        // Eski çalıştırma geç kalıp sonuç yazmaya çalışır: hiçbir şey yazılmaz
        assertTrue(writer.write(job.getId(), oldToken, repoId, List.of(debt("A.java", 1, "TODO", "stale")),
                result("a".repeat(40)), null).isEmpty());
        assertFalse(jobs.complete(job.getId(), oldToken, "FAILED", 0, 0, 0, 0, null, "late"));
        assertFalse(jobs.defer(job.getId(), oldToken, 60, "late"));
        assertFalse(jobs.release(job.getId(), oldToken));
        jobs.updateProgress(job.getId(), oldToken, 999);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM debts WHERE repo_id = ?", Integer.class, repoId));
        assertEquals(0, jobs.findById(job.getId()).orElseThrow().getFilesScanned(), "eski çalıştırma ilerleme yazamaz");

        // Yeni sahip yazabilir
        assertTrue(writer.write(job.getId(), newToken, repoId, List.of(debt("A.java", 1, "TODO", "fresh")),
                result("b".repeat(40)), null).isPresent());
        assertEquals("DONE", jobs.findById(job.getId()).orElseThrow().getStatus());
    }

    @Test
    void releaseOnShutdownLetsAnotherInstanceTakeOverImmediately() {
        migrateAll();
        ScanJob job = jobs.enqueue(repoId, userId, null);
        java.util.UUID token = jobs.claimPending(1).get(0).getClaimToken();

        assertTrue(jobs.release(job.getId(), token));

        ScanJob taken = jobs.claimPending(1).get(0);
        assertEquals(job.getId(), taken.getId());
        assertEquals(1, taken.getAttempts(), "geri bırakma deneme hakkı yakmaz");
        assertFalse(jobs.complete(job.getId(), token, "DONE", 0, 0, 0, 0, null, null), "eski jeton geçersiz");
    }

    @Test
    void quotaCountersSeeOnlyTheRightJobs() {
        migrateAll();
        long other = newUser();
        jobs.enqueue(repoId, userId, null);
        long r2 = newRepo(userId);
        jobs.enqueue(r2, userId, null);
        jobs.claimPending(1);
        jobs.enqueue(newRepo(other), other, null);
        jdbc.update("INSERT INTO scan_jobs (repo_id, user_id, status, created_at, finished_at) " +
                    "VALUES (?, ?, 'DONE', NOW() - INTERVAL '30 hours', NOW() - INTERVAL '30 hours')", r2, userId);

        assertEquals(2, jobs.countActiveByUser(userId), "PENDING + RUNNING");
        assertEquals(2, jobs.countCreatedByUserWithinHours(userId, 24), "30 saat önceki iş pencere dışında");
        assertEquals(1, jobs.countActiveByUser(other));
    }

    @Test
    void repoSizeIsStoredAndTrackedCountIsScoped() {
        migrateAll();
        RepoRepository repos = new RepoRepository(jdbc);
        var saved = repos.save(dev.hashmark.repo.model.Repo.builder()
                .userId(userId).githubRepoId("gh-big").fullName("o/big").isPrivate(false).sizeKb(2048).build());
        assertEquals(2048, saved.getSizeKb());
        // senkron boyut bildirmezse eski değer korunur
        var resynced = repos.save(dev.hashmark.repo.model.Repo.builder()
                .userId(userId).githubRepoId("gh-big").fullName("o/big").isPrivate(false).build());
        assertEquals(2048, resynced.getSizeKb());

        assertEquals(1, repos.countTrackedByUserId(userId), "aday sayılmaz");
        repos.markTracked(saved.getId(), userId);
        assertEquals(2, repos.countTrackedByUserId(userId));
    }

    @Test
    void schedulerLockAllowsOnlyOneHolder() {
        migrateAll();
        net.javacrumbs.shedlock.core.LockProvider provider = new net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider(
                net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(jdbc).usingDbTime().build());
        var config = new net.javacrumbs.shedlock.core.LockConfiguration(java.time.Instant.now(), "weekly-report",
                java.time.Duration.ofMinutes(10), java.time.Duration.ZERO);

        var first = provider.lock(config);
        assertTrue(first.isPresent());
        assertTrue(provider.lock(config).isEmpty(), "ikinci instance kilidi alamaz");

        first.get().unlock();
        var again = provider.lock(config);
        assertTrue(again.isPresent(), "bırakılınca yeniden alınabilir");
        again.get().unlock();
    }

    private void insertSnapshot(long repo, LocalDate day, int total, int added, int resolved) {
        jdbc.update("INSERT INTO scan_snapshots (repo_id, taken_at, total_open, added, resolved) VALUES (?, ?, ?, ?, ?)",
                repo, Timestamp.valueOf(day.atTime(12, 0)), total, added, resolved);
    }

    @SuppressWarnings("unused")
    private static List<Debt> none() {
        return new ArrayList<>();
    }
}
