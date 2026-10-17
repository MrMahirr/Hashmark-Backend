package dev.hashmark;

import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.auth.service.GithubTokenReencryptionJob;
import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.common.util.AesEncryptionUtil;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Anahtar dönüşü uçtan uca (gerçek PostgreSQL): eski anahtarla / eski biçimde şifrelenmiş GitHub token'ları
 * geçerli anahtara taşınır, çözülemeyen değer atlanır, ikinci çalıştırma hiçbir şey yapmaz.
 */
@EnabledIfEnvironmentVariable(named = "HASHMARK_TEST_DB_URL", matches = ".+")
class GithubTokenReencryptionTest {

    private static final String OLD = "12345678901234567890123456789012";
    private static final String NEW = "base64:" + java.util.Base64.getEncoder().encodeToString(
            "abcdefghijklmnopqrstuvwxyz012345".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static final String LOST = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";

    private DriverManagerDataSource adminDataSource;
    private String schema;
    private JdbcTemplate jdbc;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        String url = System.getenv("HASHMARK_TEST_DB_URL");
        String user = System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", "");
        schema = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        adminDataSource = new DriverManagerDataSource(url, user, password);
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(adminDataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration")
                // application.yml ile aynı: aksi halde CONCURRENTLY migrasyonu Flyway'in kendi transaction'ını bekler
                .configuration(java.util.Map.of("flyway.postgresql.transactional.lock", "false"))
                .load().migrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        ds.setSchema(schema);
        jdbc = new JdbcTemplate(ds);
        users = new UserRepository(jdbc);
    }

    @AfterEach
    void tearDown() {
        new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private static AesEncryptionUtil aes(String secret, String previous) {
        AesEncryptionUtil util = new AesEncryptionUtil();
        ReflectionTestUtils.setField(util, "secret", secret);
        ReflectionTestUtils.setField(util, "previousSecrets", previous);
        util.init();
        return util;
    }

    private long user(String token) {
        return jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_token) VALUES (?, 'a@b.c', 'N', ?) RETURNING id",
                Long.class, "gh-" + UUID.randomUUID(), token);
    }

    private String token(long id) {
        return jdbc.queryForObject("SELECT github_token FROM users WHERE id = ?", String.class, id);
    }

    @Test
    void migratesOldAndLegacyValuesToCurrentKey() throws Exception {
        AesEncryptionUtil rotated = aes(NEW, OLD);
        long underOld = user(aes(OLD, "").encrypt("gho_old"));
        long legacy = user(dev.hashmark.common.util.KeyRotationTest.legacyEncrypt(OLD, "gho_legacy"));
        String alreadyCurrentValue = rotated.encrypt("gho_current");
        long current = user(alreadyCurrentValue);
        long lost = user(aes(LOST, "").encrypt("gho_lost"));  // anahtarı listede yok
        long noToken = user(null);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GithubTokenReencryptionJob job = new GithubTokenReencryptionJob(users, rotated, new AppMetrics(registry), true);
        GithubTokenReencryptionJob.Result result = job.run();

        assertEquals(2, result.reencrypted());
        assertEquals(1, result.failed());
        AesEncryptionUtil onlyNew = aes(NEW, "");
        assertEquals("gho_old", onlyNew.decrypt(token(underOld)));
        assertEquals("gho_legacy", onlyNew.decrypt(token(legacy)));
        assertEquals(alreadyCurrentValue, token(current), "güncel değere dokunulmamalı");
        assertTrue(token(lost).startsWith("v2:" + aes(LOST, "").currentKeyId()), "çözülemeyen değer korunur");
        assertNull(token(noToken));
        assertEquals(2.0, registry.get("hashmark.crypto.reencrypted").tag("result", "ok").counter().count());

        // İdempotent: ikinci çalıştırma yalnızca çözülemeyeni yeniden görür
        GithubTokenReencryptionJob.Result again = job.run();
        assertEquals(0, again.reencrypted());
        assertEquals(1, again.failed());
    }

    @Test
    void concurrentLoginIsNotOverwritten() {
        AesEncryptionUtil rotated = aes(NEW, OLD);
        String stale = aes(OLD, "").encrypt("gho_stale");
        long id = user(stale);
        String fresh = rotated.encrypt("gho_fresh_login");
        // İş değeri okuduktan sonra kullanıcı yeniden giriş yapmış gibi: koşullu UPDATE yazmamalı
        jdbc.update("UPDATE users SET github_token = ? WHERE id = ?", fresh, id);
        assertEquals(false, users.replaceGithubToken(id, stale, rotated.reencrypt(stale)));
        assertEquals(fresh, token(id));
    }

    @Test
    void batchesPastBatchSize() {
        AesEncryptionUtil old = aes(OLD, "");
        int n = 450; // > 2 parti
        for (int i = 0; i < n; i++) {
            user(old.encrypt("gho_" + i));
        }
        GithubTokenReencryptionJob job = new GithubTokenReencryptionJob(users, aes(NEW, OLD),
                new AppMetrics(new SimpleMeterRegistry()), true);
        assertEquals(n, job.run().reencrypted());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE github_token NOT LIKE ?", Integer.class,
                "v2:" + aes(NEW, "").currentKeyId() + ":%"));
    }
}
