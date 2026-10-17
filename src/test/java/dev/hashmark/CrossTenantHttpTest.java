package dev.hashmark;

import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * Çapraz kiracı (K4), HTTP düzeyi: A kullanıcısı kendi geçerli JWT'siyle B'nin her kaynağına **tüm kimlikli
 * uçlardan** ulaşmaya çalışır. Nesne kimliği alan uçlar 403/404 dönmeli; liste/rapor uçları B'nin verisini
 * içermemeli; hiçbir deneme B'nin satırlarını değiştirmemeli. Gerçek Spring bağlamı (filtreler, güvenlik,
 * controller, servis, repository) + gerçek PostgreSQL. {@link CrossTenantIsolationTest} repository katmanıdır.
 */
@EnabledIfEnvironmentVariable(named = "HASHMARK_TEST_DB_URL", matches = ".+")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "github.client-id=test-client",
        "github.client-secret=test-secret",
        "jwt.secret=01234567890123456789012345678901",
        "encryption.secret=01234567890123456789012345678901",
        "retention.enabled=false",
        "encryption.reencrypt.enabled=false",
        // GitHub'a hiçbir istek çıkmamalı; çıkarsa kapalı porta gider ve hızlı düşer
        "GITHUB_API_URL=http://127.0.0.1:9",
        "GITHUB_OAUTH_URL=http://127.0.0.1:9",
        "scan.dispatch-interval-ms=3600000",
        "rate-limit.scan-per-minute=1000",
})
class CrossTenantHttpTest {

    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String B_CONTENT = "SENTINEL_B_CONTENT_q7w8";
    private static final String B_REPO = "victim/secret-repo-x9";
    private static final String B_NOTE = "SENTINEL_B_NOTE_k3l4";

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        String base = System.getenv("HASHMARK_TEST_DB_URL");
        registry.add("DATABASE_URL", () -> base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
        registry.add("DATABASE_USERNAME", () -> System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres"));
        registry.add("DATABASE_PASSWORD", () -> System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", ""));
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void dropSchema() {
        new JdbcTemplate(new DriverManagerDataSource(System.getenv("HASHMARK_TEST_DB_URL"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", "")))
                .execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtUtil jwt;
    @Autowired AesEncryptionUtil crypto;

    private String tokenA;
    private long repoB;
    private long debtB;
    private long notificationB;

    @BeforeEach
    void setUp() {
        long userA = user("a");
        long userB = user("b");
        tokenA = jwt.generateAccessToken(userA);
        repo(userA, "attacker/own-repo");
        repoB = repo(userB, B_REPO);
        debtB = jdbc.queryForObject(
                "INSERT INTO debts (repo_id, file_path, line_no, label, content, fingerprint, occurrence) " +
                "VALUES (?, 'src/b.java', 1, 'TODO', ?, ?, 0) RETURNING id",
                Long.class, repoB, B_CONTENT, UUID.randomUUID().toString().substring(0, 32));
        notificationB = jdbc.queryForObject(
                "INSERT INTO notifications (user_id, title, description, type) VALUES (?, ?, 'd', 'INFO') RETURNING id",
                Long.class, userB, B_NOTE);
        jdbc.update("INSERT INTO scan_snapshots (repo_id, total_open) VALUES (?, 1)", repoB);
    }

    private long user(String tag) {
        return jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_login, github_token) VALUES (?, ?, 'N', ?, ?) RETURNING id",
                Long.class, "gh-" + UUID.randomUUID(), tag + "@x.y", tag + UUID.randomUUID().toString().substring(0, 6),
                crypto.encrypt("gho_" + tag));
    }

    private long repo(long owner, String fullName) {
        return jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name, tracked) VALUES (?, ?, ?, TRUE) RETURNING id",
                Long.class, owner, "r-" + UUID.randomUUID(), fullName);
    }

    private MvcResult call(HttpMethod method, String path, String body) throws Exception {
        var req = request(method, path).header("Authorization", "Bearer " + tokenA);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(req).andReturn();
    }

    /** Nesne kimliğiyle B'ye yönelen her uç: yalnızca 403/404 kabul (200, 5xx ve 409 bir sızıntı/hata belirtisi). */
    @Test
    void objectEndpointsRefuseOtherUsersResources() throws Exception {
        record Attempt(HttpMethod method, String path, String body) {}
        List<Attempt> attempts = List.of(
                new Attempt(HttpMethod.GET, "/debts/" + debtB + "/context", null),
                new Attempt(HttpMethod.PATCH, "/debts/" + debtB, "{\"status\":\"IGNORED\",\"assignee\":\"evil\"}"),
                new Attempt(HttpMethod.PUT, "/notifications/" + notificationB + "/read", null),
                new Attempt(HttpMethod.GET, "/repos/" + repoB, null),
                new Attempt(HttpMethod.POST, "/repos/" + repoB + "/track", null),
                new Attempt(HttpMethod.PUT, "/repos/" + repoB + "/branch", "{\"branch\":\"evil\"}"),
                new Attempt(HttpMethod.PUT, "/repos/" + repoB + "/auto-scan", "{\"enabled\":true}"),
                new Attempt(HttpMethod.PUT, "/repos/" + repoB + "/auto-scan", "{\"enabled\":false}"),
                new Attempt(HttpMethod.DELETE, "/repos/" + repoB, null),
                new Attempt(HttpMethod.POST, "/scan/" + repoB, null),
                new Attempt(HttpMethod.GET, "/scan/" + repoB + "/status", null),
                // /v1 önekli yol aynı kuralları taşır
                new Attempt(HttpMethod.GET, "/v1/repos/" + repoB, null),
                new Attempt(HttpMethod.PATCH, "/v1/debts/" + debtB, "{\"status\":\"WONT_FIX\"}"));

        List<String> failures = new ArrayList<>();
        for (Attempt a : attempts) {
            MvcResult r = call(a.method(), a.path(), a.body());
            int status = r.getResponse().getStatus();
            String body = r.getResponse().getContentAsString();
            if (status != 403 && status != 404) {
                failures.add(a.method() + " " + a.path() + " → " + status);
            }
            if (body.contains(B_CONTENT) || body.contains(B_REPO) || body.contains(B_NOTE)) {
                failures.add(a.method() + " " + a.path() + " → B'nin verisi yanıtta");
            }
        }
        assertTrue(failures.isEmpty(), "Çapraz kiracı ihlali:\n" + String.join("\n", failures));
        assertVictimUnchanged();
    }

    /** repoId/q filtresiyle B'yi hedefleyen liste ve rapor uçları B'nin verisini döndürmemeli. */
    @Test
    void listAndReportEndpointsNeverIncludeOtherUsersData() throws Exception {
        List<String> paths = List.of(
                "/debts", "/debts?repoId=" + repoB, "/debts?q=" + B_CONTENT, "/debts?q=secret-repo",
                "/debts/stats", "/debts/stats?repoId=" + repoB,
                "/debts/export?format=csv", "/debts/export?format=json&repoId=" + repoB,
                "/report/summary", "/report/summary?repoId=" + repoB,
                "/notifications", "/repos", "/user/me", "/settings");
        List<String> failures = new ArrayList<>();
        for (String path : paths) {
            MvcResult r = call(HttpMethod.GET, path, null);
            int status = r.getResponse().getStatus();
            String body = r.getResponse().getContentAsString();
            if (status >= 500) {
                failures.add(path + " → " + status);
            }
            if (body.contains(B_CONTENT) || body.contains(B_REPO) || body.contains(B_NOTE)) {
                failures.add(path + " → B'nin verisi yanıtta (" + status + ")");
            }
        }
        assertTrue(failures.isEmpty(), "Çapraz kiracı ihlali:\n" + String.join("\n", failures));
        assertVictimUnchanged();
    }

    @Test
    void bulkActionsOnlyTouchOwnRows() throws Exception {
        assertTrue(call(HttpMethod.PUT, "/notifications/read-all", null).getResponse().getStatus() < 300);
        assertVictimUnchanged();
    }

    @Test
    void everyProtectedEndpointRequiresAuthentication() throws Exception {
        List<String> paths = List.of("/debts", "/debts/" + debtB + "/context", "/repos", "/repos/" + repoB,
                "/notifications", "/report/summary", "/scan/" + repoB + "/status", "/settings", "/user/me");
        for (String path : paths) {
            int status = mvc.perform(request(HttpMethod.GET, path)).andReturn().getResponse().getStatus();
            assertEquals(401, status, path + " kimliksiz erişilebilir");
        }
    }

    private void assertVictimUnchanged() {
        assertEquals("OPEN", jdbc.queryForObject("SELECT triage_status FROM debts WHERE id = ?", String.class, debtB));
        assertEquals(null, jdbc.queryForObject("SELECT assigned_to FROM debts WHERE id = ?", String.class, debtB));
        assertEquals(Boolean.FALSE,
                jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?", Boolean.class, notificationB));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM repos WHERE id = ? AND tracked AND tracked_branch IS NULL " +
                "AND webhook_secret IS NULL AND webhook_id IS NULL",
                Integer.class, repoB));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM scan_jobs WHERE repo_id = ?", Integer.class, repoB));
        assertFalse(jdbc.queryForObject("SELECT count(*) FROM debts WHERE repo_id = ?", Integer.class, repoB) == 0);
    }
}
