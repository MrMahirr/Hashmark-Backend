package dev.hashmark;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import dev.hashmark.scanner.job.ScanJobProcessor;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Onay kapısı açıkken (plan 5.10, K7): onaysız kullanıcı veri işleyemez; yalnızca gösterilen metnin sürüm+özeti
 * kabul edilir; geri çekme kapıyı yeniden kapatır; kuyruktaki iş onaysız çalışmaz; özel depo ek onay ister.
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
        "consent.enforced=true",
        "GITHUB_API_URL=http://127.0.0.1:9",
        "GITHUB_OAUTH_URL=http://127.0.0.1:9",
        "scan.dispatch-interval-ms=3600000",
        "rate-limit.scan-per-minute=1000",
})
class ConsentGateTest {

    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    @Autowired ScanJobProcessor processor;
    @Autowired ScanJobRepository jobs;

    private long userId;
    private String token;
    private long publicRepo;
    private long privateRepo;

    @BeforeEach
    void setUp() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_token) VALUES (?, 'a@b.c', 'N', ?) RETURNING id",
                Long.class, "gh-" + UUID.randomUUID(), crypto.encrypt("gho_x"));
        token = "Bearer " + jwt.generateAccessToken(userId);
        publicRepo = repo(false);
        privateRepo = repo(true);
    }

    private long repo(boolean isPrivate) {
        return jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name, private, tracked) VALUES (?, ?, ?, ?, TRUE) RETURNING id",
                Long.class, userId, "r-" + UUID.randomUUID(), "o/r" + UUID.randomUUID().toString().substring(0, 4), isPrivate);
    }

    private MvcResult call(MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.header("Authorization", token)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private void accept() throws Exception {
        JsonNode policy = json(mvc.perform(get("/public/consent-policy?lang=tr")).andReturn());
        MvcResult r = call(post("/user/consent").contentType(MediaType.APPLICATION_JSON).content(
                "{\"version\":" + policy.get("version").asInt() + ",\"language\":\"tr\",\"hash\":\"" + policy.get("hash").asString() + "\"}"));
        assertEquals(200, r.getResponse().getStatus(), r.getResponse().getContentAsString());
    }

    @Test
    void withoutConsentDataEndpointsAreBlockedButConsentFlowIsOpen() throws Exception {
        MvcResult blocked = call(get("/debts"));
        assertEquals(403, blocked.getResponse().getStatus());
        assertEquals("CONSENT_REQUIRED", json(blocked).get("code").asString());
        assertEquals(403, call(post("/scan/" + publicRepo)).getResponse().getStatus());
        assertEquals(403, call(post("/repos/sync")).getResponse().getStatus());
        // /v1 önekiyle de kapı geçerli
        assertEquals(403, call(get("/v1/debts")).getResponse().getStatus());

        JsonNode me = json(call(get("/user/me")));
        assertTrue(me.get("consentRequired").asBoolean());
        assertEquals(200, call(get("/user/consent")).getResponse().getStatus());
        // Metin kimliksiz okunabilir
        JsonNode policy = json(mvc.perform(get("/public/consent-policy?lang=en")).andReturn());
        assertTrue(policy.get("text").asString().contains("## Data we collect"));
        assertEquals(64, policy.get("hash").asString().length());
    }

    @Test
    void onlyTheDisplayedVersionAndHashCanBeAccepted() throws Exception {
        MvcResult wrongHash = call(post("/user/consent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"language\":\"tr\",\"hash\":\"" + "0".repeat(64) + "\"}"));
        assertEquals(409, wrongHash.getResponse().getStatus());
        assertEquals("POLICY_OUTDATED", json(wrongHash).get("code").asString());
        MvcResult oldVersion = call(post("/user/consent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"language\":\"tr\",\"hash\":\"x\"}"));
        assertEquals(409, oldVersion.getResponse().getStatus());

        accept();
        assertEquals(200, call(get("/debts")).getResponse().getStatus());
        JsonNode me = json(call(get("/user/me")));
        assertFalse(me.get("consentRequired").asBoolean());
        assertEquals(1, me.get("consentVersion").asInt());
        // Kayıt: metnin özeti var, IP/UA kolonu yok
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM user_consents WHERE user_id = ? AND withdrawn_at IS NULL AND length(text_hash) = 64",
                Integer.class, userId));
    }

    @Test
    void withdrawalClosesTheGateAndStopsQueuedWork() throws Exception {
        accept();
        assertEquals(202, call(post("/scan/" + publicRepo)).getResponse().getStatus());
        assertEquals(200, call(delete("/user/consent")).getResponse().getStatus());
        assertEquals(403, call(get("/debts")).getResponse().getStatus());

        // Kuyruktaki iş işçide onay denetiminden geçemez → FAILED, GitHub'a gidilmez
        ScanJob job = jobs.claimPending(10).stream().filter(j -> j.getRepoId() == publicRepo).findFirst().orElseThrow();
        processor.process(job);
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM scan_jobs WHERE id = ?", String.class, job.getId()));

        // Yeniden onay → kapı açılır; geçmiş kayıt korunur (iki satır, biri geri çekilmiş)
        accept();
        assertEquals(200, call(get("/debts")).getResponse().getStatus());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM user_consents WHERE user_id = ?", Integer.class, userId));
    }

    @Test
    void privateRepoNeedsExtraAcknowledgement() throws Exception {
        accept();
        MvcResult r = call(post("/scan/" + privateRepo));
        assertEquals(403, r.getResponse().getStatus());
        assertEquals("PRIVATE_REPO_ACK_REQUIRED", json(r).get("code").asString());

        assertEquals(204, call(post("/repos/" + privateRepo + "/private-ack")).getResponse().getStatus());
        assertEquals(202, call(post("/scan/" + privateRepo)).getResponse().getStatus());
        // Başkasının deposu onaylanamaz
        long other = jdbc.queryForObject(
                "INSERT INTO users (github_id) VALUES (?) RETURNING id", Long.class, "gh-" + UUID.randomUUID());
        long otherRepo = jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name, private) VALUES (?, ?, 'x/y', TRUE) RETURNING id",
                Long.class, other, "r-" + UUID.randomUUID());
        assertEquals(404, call(post("/repos/" + otherRepo + "/private-ack")).getResponse().getStatus());
        assertEquals(null, jdbc.queryForObject("SELECT private_ack_at FROM repos WHERE id = ?", java.sql.Timestamp.class, otherRepo));
    }

    @Test
    void accountDeletionStaysAvailableWithoutConsent() throws Exception {
        assertEquals(204, call(delete("/user/account")).getResponse().getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId));
    }
}
