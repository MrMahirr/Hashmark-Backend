package dev.hashmark;

import dev.hashmark.common.util.FingerprintUtil;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.model.Debt;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.notification.repository.NotificationRepository;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.report.repository.ReportRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Çapraz kiracı (K4): A kullanıcısı, B kullanıcısının kaynaklarına kimlikle ulaşmaya çalışır; hiçbir
 * repository yolu veri döndürmemeli ya da değiştirmemeli. Gerçek PostgreSQL gerektirir
 * ({@link DatabaseIntegrationTest} ile aynı ortam değişkenleri).
 */
@EnabledIfEnvironmentVariable(named = "HASHMARK_TEST_DB_URL", matches = ".+")
class CrossTenantIsolationTest {

    private DriverManagerDataSource adminDataSource;
    private String schema;
    private JdbcTemplate jdbc;
    private DebtRepository debts;
    private RepoRepository repos;
    private NotificationRepository notifications;
    private ReportRepository reports;

    private long userA;
    private long userB;
    private long repoB;
    private long debtB;
    private long notificationB;

    @BeforeEach
    void setUp() {
        String url = System.getenv("HASHMARK_TEST_DB_URL");
        String user = System.getenv().getOrDefault("HASHMARK_TEST_DB_USER", "postgres");
        String password = System.getenv().getOrDefault("HASHMARK_TEST_DB_PASSWORD", "");

        schema = "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        adminDataSource = new DriverManagerDataSource(url, user, password);
        new JdbcTemplate(adminDataSource).execute("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(adminDataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();

        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setSchema(schema);
        jdbc = new JdbcTemplate(dataSource);
        debts = new DebtRepository(jdbc);
        repos = new RepoRepository(jdbc);
        notifications = new NotificationRepository(jdbc);
        reports = new ReportRepository(jdbc);

        userA = newUser();
        userB = newUser();
        long repoA = newRepo(userA);
        repoB = newRepo(userB);
        debts.replaceForRepo(repoA, List.of(debt(repoA, "a.java", "TODO", "a")), "a".repeat(40), null, null);
        debts.replaceForRepo(repoB, List.of(debt(repoB, "b.java", "FIXME", "secret b")), "b".repeat(40), null, null);
        debtB = jdbc.queryForObject("SELECT id FROM debts WHERE repo_id = ?", Long.class, repoB);
        jdbc.update("INSERT INTO scan_snapshots (repo_id, total_open) VALUES (?, 1)", repoB);
        notificationB = jdbc.queryForObject(
                "INSERT INTO notifications (user_id, title, description, type) VALUES (?, 't', 'd', 'INFO') RETURNING id",
                Long.class, userB);
    }

    @AfterEach
    void tearDown() {
        new JdbcTemplate(adminDataSource).execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }

    private long newUser() {
        return jdbc.queryForObject(
                "INSERT INTO users (github_id, email, name, github_token) VALUES (?, 'a@b.c', 'N', 't') RETURNING id",
                Long.class, "gh-" + UUID.randomUUID());
    }

    private long newRepo(long owner) {
        return jdbc.queryForObject(
                "INSERT INTO repos (user_id, github_repo_id, full_name, tracked) VALUES (?, ?, 'o/r', TRUE) RETURNING id",
                Long.class, owner, "r-" + UUID.randomUUID());
    }

    private Debt debt(long repoId, String file, String label, String content) {
        return Debt.builder().repoId(repoId).filePath(file).lineNo(1).label(label).content(content)
                .fingerprint(FingerprintUtil.fingerprint(label, content)).occurrence(0).build();
    }

    @Test
    void debtsOfAnotherUserAreInvisible() {
        assertTrue(debts.findByIdForUser(userA, debtB).isEmpty());
        assertTrue(debts.findByIdForUser(userB, debtB).isPresent(), "sahibi görebilmeli (kontrol)");

        // repoId filtresi başkasının deposuna yönlendirse de sonuç boş
        DebtFilterRequest byRepo = DebtFilterRequest.builder().repoId(repoB).build();
        assertEquals(0, debts.findByFilter(userA, byRepo).getTotalElements());
        assertTrue(debts.findForExport(userA, byRepo, 100).isEmpty());

        // serbest metin araması başkasının içeriğini sızdırmaz
        DebtFilterRequest search = DebtFilterRequest.builder().q("secret").build();
        assertEquals(0, debts.findByFilter(userA, search).getTotalElements());

        assertEquals(0, debts.getStats(userA, repoB).getTotal());
    }

    @Test
    void triageOfAnotherUsersDebtChangesNothing() {
        assertTrue(debts.updateTriage(userA, debtB, "RESOLVED", "mallory").isEmpty());
        assertTrue(debts.updateTriage(userA, debtB, "IGNORED", null).isEmpty());

        assertEquals("OPEN", jdbc.queryForObject("SELECT triage_status FROM debts WHERE id = ?", String.class, debtB));
        assertNull(jdbc.queryForObject("SELECT resolved_at FROM debts WHERE id = ?", java.sql.Timestamp.class, debtB));
        assertNull(jdbc.queryForObject("SELECT assigned_to FROM debts WHERE id = ?", String.class, debtB));
    }

    @Test
    void reposOfAnotherUserAreInaccessible() {
        assertTrue(repos.findByIdAndUserId(repoB, userA).isEmpty());
        assertTrue(repos.findByIdAndUserIdWithDebtCount(repoB, userA).isEmpty());
        assertTrue(repos.findAllByUserId(userA).stream().noneMatch(r -> r.getId() == repoB));

        assertEquals(0, repos.updateTrackedBranch(repoB, userA, "evil"));
        assertEquals(0, repos.markTracked(repoB, userA));
        repos.deleteByIdAndUserId(repoB, userA);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM repos WHERE id = ?", Integer.class, repoB),
                "başkasının deposu silinemez");
        assertNull(jdbc.queryForObject("SELECT tracked_branch FROM repos WHERE id = ?", String.class, repoB));
    }

    @Test
    void notificationsOfAnotherUserAreInaccessible() {
        assertTrue(notifications.findByUserIdOrderByCreatedAtDesc(userA, 50).isEmpty());

        notifications.markAsRead(notificationB, userA);
        notifications.markAllAsRead(userA);
        assertFalse(jdbc.queryForObject("SELECT is_read FROM notifications WHERE id = ?", Boolean.class, notificationB),
                "başkasının bildirimi okundu yapılamaz");
    }

    @Test
    void reportsOfAnotherUsersRepoAreEmpty() {
        assertTrue(reports.getTrendFromSnapshots(userA, repoB, LocalDate.now()).isEmpty());
        assertTrue(reports.getTopModules(userA, repoB).isEmpty());
    }

    @Test
    void deletingOneUsersReposLeavesOthersIntact() {
        repos.deleteAllByUserId(userA);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM repos WHERE user_id = ?", Integer.class, userB));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM debts WHERE repo_id = ?", Integer.class, repoB));
    }
}
