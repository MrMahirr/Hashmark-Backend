package dev.hashmark;

import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import dev.hashmark.webhook.WebhookSignature;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Push webhook'u uçtan uca (gerçek Spring bağlamı + gerçek PostgreSQL): kimliksiz istek, HMAC doğrulama, dal
 * süzgeci, kuyruğa alma ve tarama sürerken gelen push'un birleştirilmesi.
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
        "GITHUB_API_URL=http://127.0.0.1:9",
        "GITHUB_OAUTH_URL=http://127.0.0.1:9",
        "scan.dispatch-interval-ms=3600000",
        "rate-limit.webhook-per-minute=1000",
})
class WebhookHttpTest {

    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String SECRET = "test-webhook-secret";

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
    @Autowired AesEncryptionUtil crypto;
    @Autowired JwtUtil jwt;
    @Autowired dev.hashmark.repo.repository.RepoRepository repoRepository;

    private long repoId;
    private String githubRepoId;

    @BeforeEach
    void setUp() {
        long userId = jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_login, github_token) VALUES (?, 'u@x.y', 'N', ?, ?) RETURNING id",
                Long.class, "gh-" + UUID.randomUUID(), "u" + UUID.randomUUID().toString().substring(0, 6),
                crypto.encrypt("gho_test"));
        githubRepoId = String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        repoId = jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name, tracked, webhook_id, webhook_secret) " +
                "VALUES (?, ?, 'o/r', TRUE, 77, ?) RETURNING id",
                Long.class, userId, githubRepoId, crypto.encrypt(SECRET));
    }

    private String push(String ref) {
        return "{\"ref\":\"" + ref + "\",\"deleted\":false,\"after\":\"abc\","
                + "\"repository\":{\"id\":" + githubRepoId + ",\"default_branch\":\"main\"}}";
    }

    private MvcResult deliver(long repo, String event, String body, String secret) throws Exception {
        return deliver(repo, event, body, secret, null);
    }

    private MvcResult deliver(long repo, String event, String body, String secret, String deliveryId) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        var request = post("/webhooks/github/" + repo)
                .header("X-GitHub-Event", event)
                .contentType(MediaType.APPLICATION_JSON)
                .content(bytes);
        if (deliveryId != null) {
            request.header("X-GitHub-Delivery", deliveryId);
        }
        if (secret != null) {
            request.header("X-Hub-Signature-256", WebhookSignature.sign(secret, bytes));
        }
        return mvc.perform(request).andReturn();
    }

    private int jobs(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM scan_jobs WHERE repo_id = ? AND status = ?",
                Integer.class, repoId, status);
    }

    @Test
    void signedPushToDefaultBranchQueuesScanWithoutLogin() throws Exception {
        MvcResult r = deliver(repoId, "push", push("refs/heads/main"), SECRET);

        assertEquals(202, r.getResponse().getStatus(), r.getResponse().getContentAsString());
        assertTrue(r.getResponse().getContentAsString().contains("\"queued\""));
        assertEquals("PUSH", jdbc.queryForObject(
                "SELECT triggered_by FROM scan_jobs WHERE repo_id = ?", String.class, repoId));

        // Bekleyen iş en son commit'i zaten tarayacak: ikinci push yeni iş açmaz
        MvcResult again = deliver(repoId, "push", push("refs/heads/main"), SECRET);
        assertTrue(again.getResponse().getContentAsString().contains("already_queued"));
        assertEquals(1, jobs("PENDING"));
    }

    @Test
    void pushWhileScanRunsIsCoalescedIntoOneRescan() throws Exception {
        jdbc.update("INSERT INTO scan_jobs (repo_id, user_id, status) " +
                    "SELECT id, user_id, 'RUNNING' FROM repos WHERE id = ?", repoId);

        MvcResult r = deliver(repoId, "push", push("refs/heads/main"), SECRET);

        assertTrue(r.getResponse().getContentAsString().contains("coalesced"), r.getResponse().getContentAsString());
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT rescan_requested FROM repos WHERE id = ?",
                Boolean.class, repoId));
        assertEquals(0, jobs("PENDING"));

        // İş bitince işlemci isteği tam bir kez tüketir
        jdbc.update("UPDATE scan_jobs SET status = 'DONE' WHERE repo_id = ?", repoId);
        assertTrue(repoRepository.consumeRescanRequest(repoId));
        assertTrue(!repoRepository.consumeRescanRequest(repoId));
    }

    @Test
    void rejectsMissingOrWrongSignatureAndUnknownRepo() throws Exception {
        assertEquals(401, deliver(repoId, "push", push("refs/heads/main"), null).getResponse().getStatus());
        assertEquals(401, deliver(repoId, "push", push("refs/heads/main"), "wrong").getResponse().getStatus());
        assertEquals(404, deliver(repoId + 999_999, "push", push("refs/heads/main"), SECRET).getResponse().getStatus());

        jdbc.update("UPDATE repos SET webhook_secret = NULL, webhook_id = NULL WHERE id = ?", repoId);
        assertEquals(404, deliver(repoId, "push", push("refs/heads/main"), SECRET).getResponse().getStatus(),
                "otomatik tarama kapatılınca teslimat reddedilir");
        assertEquals(0, jobs("PENDING"));
    }

    @Test
    void ignoresOtherBranchesTagsDeletesAndEvents() throws Exception {
        assertTrue(deliver(repoId, "push", push("refs/heads/feature"), SECRET)
                .getResponse().getContentAsString().contains("not tracked"));
        assertTrue(deliver(repoId, "push", push("refs/tags/v1"), SECRET)
                .getResponse().getContentAsString().contains("not a branch push"));
        assertTrue(deliver(repoId, "push", push("refs/heads/main").replace("\"deleted\":false", "\"deleted\":true"), SECRET)
                .getResponse().getContentAsString().contains("branch deleted"));
        assertTrue(deliver(repoId, "issues", "{}", SECRET)
                .getResponse().getContentAsString().contains("not handled"));
        assertEquals(200, deliver(repoId, "ping", "{\"zen\":\"x\"}", SECRET).getResponse().getStatus());
        assertEquals(0, jobs("PENDING"));

        // İzlenen dal ayarlıysa varsayılan dal değil o dal taranır
        jdbc.update("UPDATE repos SET tracked_branch = 'feature' WHERE id = ?", repoId);
        assertEquals(202, deliver(repoId, "push", push("refs/heads/feature"), SECRET).getResponse().getStatus());
        assertEquals(1, jobs("PENDING"));
        assertEquals("feature", jdbc.queryForObject("SELECT branch FROM scan_jobs WHERE repo_id = ?", String.class, repoId));
    }

    @Test
    void ignoresPayloadForAnotherRepository() throws Exception {
        String other = push("refs/heads/main").replace("\"id\":" + githubRepoId, "\"id\":1");

        assertTrue(deliver(repoId, "push", other, SECRET).getResponse().getContentAsString().contains("does not match"));
        assertEquals(0, jobs("PENDING"));
    }

    private String pullRequest(String action, int number, String head) {
        return "{\"action\":\"" + action + "\",\"number\":" + number + ","
                + "\"pull_request\":{\"number\":" + number + ",\"head\":{\"sha\":\"" + head + "\"},"
                + "\"base\":{\"sha\":\"" + "b".repeat(40) + "\"}},"
                + "\"repository\":{\"id\":" + githubRepoId + ",\"default_branch\":\"main\"}}";
    }

    private int prJobs(String status) {
        return jdbc.queryForObject("SELECT count(*) FROM scan_jobs WHERE repo_id = ? AND kind = 'PR' AND status = ?",
                Integer.class, repoId, status);
    }

    @Test
    void pullRequestQueuesOneCheckPerPrAlongsideScans() throws Exception {
        // Depoda çalışan bir tarama varken de PR kontrolü kuyruğa girer (V17: tür başına indeks)
        jdbc.update("INSERT INTO scan_jobs (repo_id, user_id, status) SELECT id, user_id, 'RUNNING' FROM repos WHERE id = ?",
                repoId);

        MvcResult opened = deliver(repoId, "pull_request", pullRequest("opened", 7, "1".repeat(40)), SECRET);
        assertEquals(202, opened.getResponse().getStatus(), opened.getResponse().getContentAsString());
        assertTrue(opened.getResponse().getContentAsString().contains("\"queued\""));

        // Aynı PR'a yeni commit: bekleyen tek kontrol en son commit'e güncellenir
        deliver(repoId, "pull_request", pullRequest("synchronize", 7, "2".repeat(40)), SECRET);
        assertEquals(1, prJobs("PENDING"));
        assertEquals("2".repeat(40), jdbc.queryForObject(
                "SELECT pr_head_sha FROM scan_jobs WHERE repo_id = ? AND kind = 'PR'", String.class, repoId));
        assertEquals("PULL_REQUEST", jdbc.queryForObject(
                "SELECT triggered_by FROM scan_jobs WHERE repo_id = ? AND kind = 'PR'", String.class, repoId));

        // Başka PR ayrı kontrol; kapanan PR kontrol edilmez
        deliver(repoId, "pull_request", pullRequest("opened", 8, "3".repeat(40)), SECRET);
        assertTrue(deliver(repoId, "pull_request", pullRequest("closed", 9, "4".repeat(40)), SECRET)
                .getResponse().getContentAsString().contains("not checked"));
        assertEquals(2, prJobs("PENDING"));

        // Tarama durumu ucu PR işini "son tarama" sanmaz
        long userId = jdbc.queryForObject("SELECT user_id FROM repos WHERE id = ?", Long.class, repoId);
        String status = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/scan/" + repoId + "/status").header("Authorization", "Bearer " + jwt.generateAccessToken(userId)))
                .andReturn().getResponse().getContentAsString();
        assertTrue(status.contains("\"kind\":\"SCAN\""), status);
    }

    @Test
    void malformedPullRequestIsRejected() throws Exception {
        assertEquals(400, deliver(repoId, "pull_request", pullRequest("opened", 7, "not-a-sha"), SECRET)
                .getResponse().getStatus());
        assertEquals(0, prJobs("PENDING"));
    }

    @Test
    void redeliveredWebhookIsProcessedOnce() throws Exception {
        String id = java.util.UUID.randomUUID().toString();

        assertTrue(deliver(repoId, "push", push("refs/heads/main"), SECRET, id).getResponse().getContentAsString()
                .contains("\"queued\""));
        jdbc.update("DELETE FROM scan_jobs WHERE repo_id = ?", repoId);
        MvcResult again = deliver(repoId, "push", push("refs/heads/main"), SECRET, id);

        assertEquals(202, again.getResponse().getStatus());
        assertTrue(again.getResponse().getContentAsString().contains("duplicate"));
        assertEquals(0, jobs("PENDING"), "yeniden gönderim yeni iş açmamalı");
        // İmzasız istek tekilleştirme kaydı bırakmaz
        String other = java.util.UUID.randomUUID().toString();
        deliver(repoId, "push", push("refs/heads/main"), "wrong", other);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE delivery_id = ?",
                Integer.class, other));
    }

    @Test
    void autoScanToggleNeedsPublicUrlAndDisableAlwaysClearsLocalState() throws Exception {
        long userId = jdbc.queryForObject("SELECT user_id FROM repos WHERE id = ?", Long.class, repoId);
        String bearer = "Bearer " + jwt.generateAccessToken(userId);

        // webhooks.public-url boş: GitHub'a hiç gidilmeden 503
        MvcResult enable = mvc.perform(put("/repos/" + repoId + "/auto-scan").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andReturn();
        assertEquals(503, enable.getResponse().getStatus());

        // GitHub'a ulaşılamasa da (kapalı port) yerel kayıt temizlenir ve yanıt autoScan=false döner
        MvcResult disable = mvc.perform(put("/repos/" + repoId + "/auto-scan").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")).andReturn();
        assertEquals(200, disable.getResponse().getStatus(), disable.getResponse().getContentAsString());
        assertTrue(disable.getResponse().getContentAsString().contains("\"autoScan\":false"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM repos WHERE id = ? AND (webhook_id IS NOT NULL OR webhook_secret IS NOT NULL)",
                Integer.class, repoId));
        assertTrue(!disable.getResponse().getContentAsString().contains("webhookSecret"));
    }
}
