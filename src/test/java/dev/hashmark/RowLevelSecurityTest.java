package dev.hashmark;

import com.sun.net.httpserver.HttpServer;
import dev.hashmark.common.mail.EmailSender;
import dev.hashmark.common.metrics.ScanQueueMetrics;
import dev.hashmark.common.retention.DataRetentionJob;
import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import dev.hashmark.report.scheduler.WeeklyReportScheduler;
import dev.hashmark.scanner.job.ScanJobDispatcher;
import dev.hashmark.scanner.service.TarTestSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Row-Level Security (V11) gerçekten koruyor mu ve uygulama RLS altında **çalışmaya devam ediyor mu**?
 * Uygulama, üretimdeki gibi tablo sahibi ve süper kullanıcı OLMAYAN bir rolle bağlanır; Flyway sahip rolle
 * çalışır (docs/ROW_LEVEL_SECURITY.md). Kapsanan yollar: kapsamsız bağlantı, kullanıcı kapsamı (WHERE'siz
 * sorgu dahil), HTTP uçları, tarama kuyruğu uçtan uca (sahte GitHub), saklama işi, kuyruk metriği, herkese
 * açık sayaç ve haftalık rapor.
 */
@EnabledIfEnvironmentVariable(named = "HASHMARK_TEST_DB_URL", matches = ".+")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "github.client-id=test-client",
        "github.client-secret=test-secret",
        "jwt.secret=01234567890123456789012345678901",
        "encryption.secret=01234567890123456789012345678901",
        "retention.enabled=true",
        "retention.cron=0 0 0 1 1 *",
        "encryption.reencrypt.enabled=false",
        "scan.dispatch-interval-ms=3600000",
        "scan.blame-max-files=0",
        "rate-limit.scan-per-minute=1000",
})
class RowLevelSecurityTest {

    static final String APP_ROLE = "hashmark_app_it";
    static final String APP_PASSWORD = "it_app_pass";
    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static HttpServer github;

    static JdbcTemplate admin() {
        return new JdbcTemplate(new DriverManagerDataSource(System.getenv("HASHMARK_TEST_DB_URL"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", "")));
    }

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) throws Exception {
        // Üretimdeki scripts/create-app-role.sql ile aynı yetki modeli: DML var, tablo sahipliği/RLS atlama yok
        JdbcTemplate admin = admin();
        admin.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + APP_ROLE + "') THEN "
                + "CREATE ROLE " + APP_ROLE + " LOGIN PASSWORD '" + APP_PASSWORD + "' NOSUPERUSER NOBYPASSRLS; END IF; END $$");
        admin.execute("CREATE SCHEMA " + SCHEMA);
        admin.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + APP_ROLE);
        admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + SCHEMA
                + " GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO " + APP_ROLE);
        admin.execute("ALTER DEFAULT PRIVILEGES IN SCHEMA " + SCHEMA + " GRANT USAGE, SELECT ON SEQUENCES TO " + APP_ROLE);

        github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = github.getAddress().getPort();
        byte[] archive = new TarTestSupport().globalHeader()
                .file("own-repo-abc/src/Main.java", "class Main {\n  // TODO: RLS_SCANNED_COMMENT\n}\n").tarGz();
        github.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/tarball")) {
                ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/codeload/a.tgz");
                ex.sendResponseHeaders(302, -1);
            } else if (path.startsWith("/codeload/")) {
                ex.sendResponseHeaders(200, archive.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(archive);
                }
            } else {
                ex.sendResponseHeaders(404, -1);
            }
            ex.close();
        });
        github.start();

        String base = System.getenv("HASHMARK_TEST_DB_URL");
        registry.add("DATABASE_URL", () -> base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("DATABASE_USERNAME", () -> APP_ROLE);
        registry.add("DATABASE_PASSWORD", () -> APP_PASSWORD);
        registry.add("FLYWAY_USER", () -> System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres"));
        registry.add("FLYWAY_PASSWORD", () -> System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("GITHUB_API_URL", () -> "http://127.0.0.1:" + port);
        registry.add("GITHUB_OAUTH_URL", () -> "http://127.0.0.1:" + port);
    }

    @AfterAll
    static void cleanUp() {
        github.stop(0);
        admin().execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        // Rol kümeye aittir; başka şemalarda varsayılan yetkisi kalmadıysa silinebilir, kalırsa sorun değil
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;          // uygulama rolü (RLS'e tabi)
    @Autowired JwtUtil jwt;
    @Autowired AesEncryptionUtil crypto;
    @Autowired ScanJobDispatcher dispatcher;
    @Autowired DataRetentionJob retention;
    @Autowired ScanQueueMetrics queueMetrics;
    @Autowired WeeklyReportScheduler weekly;
    @MockitoBean EmailSender emailSender;

    private JdbcTemplate owner;            // sahip rol (RLS'i atlar) — yalnızca kurulum ve doğrulama
    private long userA;
    private long userB;
    private long repoA;
    private long repoB;
    private long debtA;
    private long debtB;

    @BeforeEach
    void setUp() {
        owner = admin();
        userA = user();
        userB = user();
        repoA = repo(userA, "own/repo-a");
        repoB = repo(userB, "victim/repo-b");
        debtA = debt(repoA, "RLS_A_CONTENT");
        debtB = debt(repoB, "RLS_B_CONTENT");
    }

    private long user() {
        return owner.queryForObject(
                "INSERT INTO " + SCHEMA + ".users (github_id, email, name, github_login, github_token) " +
                "VALUES (?, 'u@x.y', 'N', ?, ?) RETURNING id",
                Long.class, "gh-" + UUID.randomUUID(), "l" + UUID.randomUUID().toString().substring(0, 6),
                crypto.encrypt("gho_test"));
    }

    private long repo(long userId, String fullName) {
        return owner.queryForObject(
                "INSERT INTO " + SCHEMA + ".repos (user_id, github_repo_id, full_name, tracked) VALUES (?, ?, ?, TRUE) RETURNING id",
                Long.class, userId, "r-" + UUID.randomUUID(), fullName);
    }

    private long debt(long repoId, String content) {
        return owner.queryForObject(
                "INSERT INTO " + SCHEMA + ".debts (repo_id, file_path, line_no, label, content, fingerprint, occurrence) " +
                "VALUES (?, 'x.java', 1, 'TODO', ?, ?, 0) RETURNING id",
                Long.class, repoId, content, UUID.randomUUID().toString().substring(0, 32));
    }

    private long ownerCount(String sql, Object... args) {
        return owner.queryForObject(sql.replace("FROM ", "FROM " + SCHEMA + "."), Long.class, args);
    }

    @Autowired dev.hashmark.common.metrics.DatabaseHealthMetrics healthMetrics;

    @Test
    void appRoleIsNotOwnerNorSuperuser() {
        assertEquals(APP_ROLE, jdbc.queryForObject("SELECT current_user", String.class));
        assertEquals(Boolean.TRUE, healthMetrics.rlsEnforced());
        // Tablo metrikleri uygulama rolüyle de okunur
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        healthMetrics.bindTo(registry);
        assertEquals(1.0, registry.get("hashmark.db.rls.enforced").gauge().value());
        assertTrue(registry.get("hashmark.db.table.size.bytes").tag("table", "debts").gauge().value() > 0);
        // pg_stat_archiver süper kullanıcı olmayan rolle de okunur (test sunucusunda arşiv kapalı → hata yok)
        assertEquals(0.0, registry.get("hashmark.db.wal.archive.failing").gauge().value());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class));
    }

    @Test
    void connectionWithoutScopeSeesNothing() {
        assertTrue(ownerCount("SELECT count(*) FROM debts") >= 2);
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM debts", Long.class));
        assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM repos", Long.class));
        assertEquals(0, jdbc.update("UPDATE debts SET triage_status = 'IGNORED'"));
    }

    @Test
    void userScopeHidesOtherTenantsEvenWithoutWhereClause() {
        TenantContext.asUser(userA, () -> {
            // Uygulama kodu kullanıcı filtresini unutsa bile yalnızca A'nın satırları görünür/değişir
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM debts", Long.class));
            assertEquals(1L, jdbc.queryForObject("SELECT count(*) FROM repos", Long.class));
            assertEquals(1, jdbc.update("UPDATE debts SET assigned_to = 'x'"));
            assertEquals(0, jdbc.update("DELETE FROM repos WHERE id = ?", repoB));
            // Başkasının deposuna satır yazılamaz (WITH CHECK → 42501)
            var ex = assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                    "INSERT INTO debts (repo_id, file_path, line_no, label, content, fingerprint) VALUES (?, 'e', 1, 'TODO', 'e', 'e')",
                    repoB));
            assertEquals("42501", ((SQLException) ex.getMostSpecificCause()).getSQLState());
        });
        assertEquals(null, owner.queryForObject("SELECT assigned_to FROM " + SCHEMA + ".debts WHERE id = ?", String.class, debtB));
        assertEquals(1L, ownerCount("SELECT count(*) FROM repos WHERE id = ?", repoB));
    }

    @Test
    void httpEndpointsWorkForOwnDataAndRefuseOthers() throws Exception {
        String token = "Bearer " + jwt.generateAccessToken(userA);
        String list = mvc.perform(get("/debts").header("Authorization", token)).andReturn().getResponse().getContentAsString();
        assertTrue(list.contains("RLS_A_CONTENT"));
        assertFalse(list.contains("RLS_B_CONTENT"));

        assertEquals(200, mvc.perform(patch("/debts/" + debtA).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IGNORED\"}")).andReturn().getResponse().getStatus());
        assertEquals(404, mvc.perform(patch("/debts/" + debtB).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IGNORED\"}")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/report/summary").header("Authorization", token)).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/settings").header("Authorization", token)).andReturn().getResponse().getStatus());
        assertEquals("OPEN", owner.queryForObject("SELECT triage_status FROM " + SCHEMA + ".debts WHERE id = ?", String.class, debtB));
    }

    @Test
    void scanPipelineRunsEndToEndUnderRls() throws Exception {
        String token = "Bearer " + jwt.generateAccessToken(userA);
        assertEquals(202, mvc.perform(post("/scan/" + repoA).header("Authorization", token)).andReturn().getResponse().getStatus());

        dispatcher.tick(); // sistem kapsamı: sahiplenme; işçi: A'nın kapsamı
        String status = null;
        for (int i = 0; i < 100 && !"DONE".equals(status) && !"FAILED".equals(status); i++) {
            Thread.sleep(100);
            status = owner.queryForObject("SELECT status FROM " + SCHEMA + ".scan_jobs WHERE repo_id = ? ORDER BY id DESC LIMIT 1",
                    String.class, repoA);
        }
        assertEquals("DONE", status, "tarama RLS altında tamamlanmalı");
        assertEquals(1L, ownerCount("SELECT count(*) FROM debts WHERE repo_id = ? AND content LIKE '%RLS_SCANNED_COMMENT%' AND resolved_at IS NULL", repoA));
        assertEquals(1L, ownerCount("SELECT count(*) FROM scan_snapshots WHERE repo_id = ?", repoA));
        assertTrue(ownerCount("SELECT count(*) FROM notifications WHERE user_id = ?", userA) >= 1);
        // B'nin borcu ve deposu etkilenmedi
        assertEquals(1L, ownerCount("SELECT count(*) FROM debts WHERE id = ? AND resolved_at IS NULL", debtB));
    }

    @Test
    void systemJobsSeeAllTenants() throws Exception {
        // Saklama: her iki kullanıcının eski işleri silinir (deponun son işi kalır)
        for (long repo : new long[]{repoA, repoB}) {
            for (int i = 0; i < 2; i++) {
                owner.update("INSERT INTO " + SCHEMA + ".scan_jobs (repo_id, status, finished_at, created_at) "
                        + "VALUES (?, 'DONE', NOW() - INTERVAL '200 days', NOW() - INTERVAL '200 days')", repo);
            }
        }
        retention.run();
        assertEquals(1L, ownerCount("SELECT count(*) FROM scan_jobs WHERE repo_id = ?", repoA));
        assertEquals(1L, ownerCount("SELECT count(*) FROM scan_jobs WHERE repo_id = ?", repoB));

        // Kuyruk metriği tüm kullanıcıların bekleyen işlerini sayar
        owner.update("DELETE FROM " + SCHEMA + ".scan_jobs WHERE status = 'PENDING'");
        owner.update("INSERT INTO " + SCHEMA + ".scan_jobs (repo_id, user_id, status) VALUES (?, ?, 'PENDING')", repoB, userB);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        queueMetrics.bindTo(registry);
        assertEquals(1.0, registry.get("hashmark.scan.queue.pending").gauge().value());
        owner.update("DELETE FROM " + SCHEMA + ".scan_jobs WHERE status = 'PENDING'");

        // Herkese açık sayaç kimliksiz çağrılır ve tüm kullanıcıları sayar
        String stats = mvc.perform(get("/public/stats")).andReturn().getResponse().getContentAsString();
        long markers = Long.parseLong(stats.replaceAll(".*\"trackedMarkers\":(\\d+).*", "$1"));
        assertEquals(ownerCount("SELECT count(*) FROM debts d JOIN " + SCHEMA + ".repos r ON r.id = d.repo_id WHERE r.tracked AND d.resolved_at IS NULL"), markers);
    }

    @Test
    void weeklyReportFindsRecipientsAndWritesUnderOwnScope() {
        // Bugün 10:00 İstanbul: haftalık, 08:00, bugünün günü → vadesi gelmiş
        java.time.ZoneId istanbul = java.time.ZoneId.of("Europe/Istanbul");
        LocalDate today = LocalDate.now(istanbul);
        java.time.Instant at = today.atTime(10, 0).atZone(istanbul).toInstant();
        owner.update("INSERT INTO " + SCHEMA + ".user_settings (user_id, email_notify, notify_day) VALUES (?, TRUE, ?)",
                userA, today.getDayOfWeek().name());
        when(emailSender.isConfigured()).thenReturn(true);
        when(emailSender.send(anyString(), anyString(), anyString(), anyString())).thenReturn(true);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(weekly, "sendDue", at);

        verify(emailSender).send(eq("u@x.y"), anyString(), anyString(), contains("<"));
        assertEquals(java.sql.Date.valueOf(today), owner.queryForObject(
                "SELECT last_digest_sent_on FROM " + SCHEMA + ".user_settings WHERE user_id = ?", java.sql.Date.class, userA));
    }

    /**
     * Kimliksiz webhook isteği: depo sistem kapsamında bulunur, iş sahibinin kapsamında kuyruğa alınır; tarama
     * sürerken gelen push birleştirilir ve işçi iş bitince yeni taramayı kendi kapsamında açar.
     */
    @Test
    void pushWebhookQueuesAndCoalescesUnderRls() throws Exception {
        String githubId = owner.queryForObject("SELECT github_repo_id FROM " + SCHEMA + ".repos WHERE id = ?",
                String.class, repoA);
        owner.update("UPDATE " + SCHEMA + ".repos SET webhook_id = 1, webhook_secret = ? WHERE id = ?",
                crypto.encrypt("rls-secret"), repoA);
        byte[] body = ("{\"ref\":\"refs/heads/main\",\"repository\":{\"id\":\"" + githubId
                + "\",\"default_branch\":\"main\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.function.Function<Long, String> deliver = repo -> {
            try {
                return mvc.perform(post("/webhooks/github/" + repo).header("X-GitHub-Event", "push")
                                .header("X-Hub-Signature-256", dev.hashmark.webhook.WebhookSignature.sign("rls-secret", body))
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        assertTrue(deliver.apply(repoA).contains("\"queued\""));
        assertTrue(deliver.apply(repoB).contains("Unknown webhook"), "anahtarı olmayan depo 404");

        // İş çalışıyormuş gibi: yeni push birleştirilir
        owner.update("UPDATE " + SCHEMA + ".scan_jobs SET status = 'RUNNING' WHERE repo_id = ?", repoA);
        assertTrue(deliver.apply(repoA).contains("coalesced"));
        assertEquals(Boolean.TRUE, owner.queryForObject(
                "SELECT rescan_requested FROM " + SCHEMA + ".repos WHERE id = ?", Boolean.class, repoA));

        // Gerçek işçi taramayı bitirir ve isteği tüketip yeni PUSH işi açar
        owner.update("UPDATE " + SCHEMA + ".scan_jobs SET status = 'PENDING' WHERE repo_id = ?", repoA);
        dispatcher.tick();
        long pushJobs = 0;
        for (int i = 0; i < 100 && pushJobs < 2; i++) {
            Thread.sleep(100);
            pushJobs = ownerCount("SELECT count(*) FROM scan_jobs WHERE repo_id = ? AND triggered_by = 'PUSH'", repoA);
        }
        assertEquals(2L, pushJobs, "iş bitince yeniden tarama kuyruğa alınmalı");
        assertEquals(1L, ownerCount("SELECT count(*) FROM scan_jobs WHERE repo_id = ? AND status = 'DONE'", repoA));
        assertEquals(1L, ownerCount("SELECT count(*) FROM scan_jobs WHERE repo_id = ? AND status = 'PENDING'", repoA));
        assertEquals(Boolean.FALSE, owner.queryForObject(
                "SELECT rescan_requested FROM " + SCHEMA + ".repos WHERE id = ?", Boolean.class, repoA));
        assertEquals(0L, ownerCount("SELECT count(*) FROM scan_jobs WHERE repo_id = ?", repoB));

        // PR kontrolü de sahibin kapsamında kuyruğa yazılır (RLS WITH CHECK)
        byte[] pr = ("{\"action\":\"opened\",\"pull_request\":{\"number\":3,\"head\":{\"sha\":\"" + "a".repeat(40)
                + "\"},\"base\":{\"sha\":\"" + "b".repeat(40) + "\"}},\"repository\":{\"id\":\"" + githubId + "\"}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String prResult = mvc.perform(post("/webhooks/github/" + repoA).header("X-GitHub-Event", "pull_request")
                        .header("X-GitHub-Delivery", UUID.randomUUID().toString())
                        .header("X-Hub-Signature-256", dev.hashmark.webhook.WebhookSignature.sign("rls-secret", pr))
                        .contentType(MediaType.APPLICATION_JSON).content(pr))
                .andReturn().getResponse().getContentAsString();
        assertTrue(prResult.contains("\"queued\""), prResult);
        assertEquals(1L, ownerCount("SELECT count(*) FROM scan_jobs WHERE repo_id = ? AND kind = 'PR' AND user_id = ?",
                repoA, userA));
        owner.update("DELETE FROM " + SCHEMA + ".scan_jobs WHERE repo_id = ?", repoA);
    }
}
