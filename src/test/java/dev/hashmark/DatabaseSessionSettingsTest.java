package dev.hashmark;

import dev.hashmark.common.metrics.DatabaseHealthMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uygulama havuzundaki her bağlantıya oturum sınırları (statement/lock/idle-in-transaction) uygulanıyor mu,
 * Flyway bu ayarları havuza sızdırmadan kendi bağlantısıyla mı çalışıyor ve zaman aşımları doğru Spring
 * istisnasına (→ 503) çevriliyor mu? Gerçek Spring bağlamı + gerçek PostgreSQL.
 */
@EnabledIfEnvironmentVariable(named = "HASHMARK_TEST_DB_URL", matches = ".+")
@SpringBootTest(properties = {
        "github.client-id=test-client",
        "github.client-secret=test-secret",
        "jwt.secret=01234567890123456789012345678901",
        "encryption.secret=01234567890123456789012345678901",
        "retention.enabled=false",
        // Testte kısa tutulur ki zaman aşımı gerçekten tetiklensin
        "DB_STATEMENT_TIMEOUT=1s",
        "DB_LOCK_TIMEOUT=500ms",
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1",
})
class DatabaseSessionSettingsTest {

    private static final String SCHEMA = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

    @DynamicPropertySource
    static void db(DynamicPropertyRegistry registry) {
        String base = System.getenv("HASHMARK_TEST_DB_URL");
        String url = base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        registry.add("DATABASE_URL", () -> url);
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DatabaseHealthMetrics healthMetrics;

    @Autowired
    org.flywaydb.core.Flyway flyway;

    @Test
    void flywayUsesSessionLockAndOwnConnection() {
        // transactional-lock=true iken CREATE INDEX CONCURRENTLY kendi kendini bekler (MigrationConventionsTest).
        var pg = flyway.getConfiguration().getPluginRegister()
                .getPlugin(org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension.class);
        assertEquals(false, pg.isTransactionalLock());
        assertTrue(flyway.getConfiguration().getInitSql().contains("statement_timeout = 0"));
        assertTrue(!(flyway.getConfiguration().getDataSource() instanceof com.zaxxer.hikari.HikariDataSource),
                "Flyway uygulama havuzunu kullanmamalı (oturum ayarları havuza sızar)");
    }

    @Test
    void poolConnectionsCarrySessionLimits() {
        // Havuzdaki her bağlantı (Flyway'in kullandığı dahil olsaydı statement_timeout=0 kalırdı) sınırı taşır.
        for (int i = 0; i < 5; i++) {
            assertEquals("1s", jdbc.queryForObject("SHOW statement_timeout", String.class));
            assertEquals("500ms", jdbc.queryForObject("SHOW lock_timeout", String.class));
            assertEquals("1min", jdbc.queryForObject("SHOW idle_in_transaction_session_timeout", String.class));
        }
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success", Integer.class));
    }

    @Test
    void runawayQueryIsCancelledAsQueryTimeout() {
        assertThrows(QueryTimeoutException.class, () -> jdbc.execute("SELECT pg_sleep(3)"));
        // Bağlantı havuzu sağlıklı kalır
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
    }

    @Test
    void lockWaitFailsFastWithLockNotAvailable() throws Exception {
        // Ayrı (havuz dışı) bir bağlantı tabloyu kilitler — migrasyonun ACCESS EXCLUSIVE kilidini taklit eder.
        DriverManagerDataSource raw = new DriverManagerDataSource(System.getenv("HASHMARK_TEST_DB_URL"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres"),
                System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", ""));
        try (Connection holder = raw.getConnection(); Statement st = holder.createStatement()) {
            holder.setAutoCommit(false);
            st.execute("LOCK TABLE " + SCHEMA + ".notifications IN ACCESS EXCLUSIVE MODE");
            long started = System.nanoTime();
            // Spring 7 55P03'ü sınıflandırmaz (UncategorizedSQLException); GlobalExceptionHandler bunu 503'e çevirir.
            UncategorizedSQLException ex = assertThrows(UncategorizedSQLException.class,
                    () -> jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class));
            assertEquals("55P03", ex.getSQLException().getSQLState());
            long waitedMs = (System.nanoTime() - started) / 1_000_000;
            assertTrue(waitedMs < 2_000, "kilit beklemesi lock_timeout ile sınırlı olmalı, beklenen <2 sn, gerçek " + waitedMs);
            holder.rollback();
        }
    }

    /**
     * Veri minimizasyonu: pgjdbc varsayılan ayarda NOT NULL ihlalinde "Failing row contains (..., yorum, ...)"
     * ayrıntısını ve toplu yazımda parametreleri istisna mesajına gömer (gerçek PG'de doğrulandı). Bu istisna
     * tarama işçisinde loglanır ve Sentry'ye gider; logServerErrorDetail=false ile içerik mesajda yer almaz.
     */
    @Test
    void failedBatchWriteDoesNotEmbedCommentInException() {
        String secret = "SENTINEL_BATCH_COMMENT_" + "z9y8";
        Long userId = jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_token) VALUES (?, 'a@b.c', 'N', 't') RETURNING id",
                Long.class, "gh-" + UUID.randomUUID());
        Long repoId = jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name, tracked) VALUES (?, ?, 'o/r', TRUE) RETURNING id",
                Long.class, userId, "r-" + UUID.randomUUID());
        var debts = new dev.hashmark.debt.repository.DebtRepository(jdbc);
        // label NULL → NOT NULL ihlali; sunucu satırın tamamını DETAIL'de döner
        var bad = dev.hashmark.debt.model.Debt.builder().repoId(repoId).filePath("a.java").lineNo(1)
                .label(null).content(secret).fingerprint("f").occurrence(0).build();

        Exception ex = assertThrows(Exception.class,
                () -> debts.replaceForRepo(repoId, java.util.List.of(bad), "a".repeat(40), null, null));
        java.io.StringWriter trace = new java.io.StringWriter();
        ex.printStackTrace(new java.io.PrintWriter(trace));
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException sql && sql.getNextException() != null) {
                sql.getNextException().printStackTrace(new java.io.PrintWriter(trace));
            }
        }
        assertTrue(trace.toString().contains("not-null"), "hata türü görünmeli");
        assertTrue(!trace.toString().contains(secret), "yorum metni istisna mesajına gömüldü:\n" + trace);
    }

    @Test
    void tableHealthMetricsReadFromCatalog() {
        jdbc.execute("ANALYZE users");
        MeterRegistry registry = new SimpleMeterRegistry();
        healthMetrics.bindTo(registry);
        double size = registry.get("hashmark.db.table.size.bytes").tag("table", "debts").gauge().value();
        assertTrue(size > 0, "debts tablo boyutu okunmalı");
        assertEquals(0.0, registry.get("hashmark.db.table.dead.rows").tag("table", "users").gauge().value());
    }
}
