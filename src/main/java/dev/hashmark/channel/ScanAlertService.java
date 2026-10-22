package dev.hashmark.channel;

import dev.hashmark.channel.NotificationChannelService.Purpose;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.notification.service.NotificationService;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Tarama sonrası uyarılar (depo başına): açık borç kullanıcının eşiğini <b>yeni aştıysa</b> (önceki tarama
 * eşikte ya da altındaydı) ve yeni sızan sır bulunduysa. Kanallara ve uygulama içi bildirime gider.
 * Hiçbir hata taramayı bozmaz.
 */
@Service
public class ScanAlertService {

    private static final Logger log = LoggerFactory.getLogger(ScanAlertService.class);

    /** Açık borç (NOTE/DOC/INFO hariç) ve açık kayıtlardaki sırlar. */
    public record Snapshot(int openDebts, int secrets) {
        public static final Snapshot EMPTY = new Snapshot(0, 0);
    }

    private final JdbcTemplate jdbcTemplate;
    private final UserSettingsRepository settingsRepository;
    private final NotificationChannelService channels;
    private final ChannelMessages messages;
    private final NotificationService notifications;

    public ScanAlertService(JdbcTemplate jdbcTemplate, UserSettingsRepository settingsRepository,
                            NotificationChannelService channels, ChannelMessages messages,
                            NotificationService notifications) {
        this.jdbcTemplate = jdbcTemplate;
        this.settingsRepository = settingsRepository;
        this.channels = channels;
        this.messages = messages;
        this.notifications = notifications;
    }

    public Snapshot snapshot(long repoId) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FILTER (WHERE d.label NOT IN ('NOTE', 'DOC', 'INFO')) AS open_debts, " +
                    "COUNT(*) FILTER (WHERE d.secret_detected) AS secrets " +
                    "FROM debts d WHERE d.repo_id = ? AND " + DebtRepository.ACTIVE,
                    (rs, i) -> new Snapshot(rs.getInt("open_debts"), rs.getInt("secrets")), repoId);
        } catch (RuntimeException e) {
            log.warn("Alert snapshot failed for repo {}: {}", repoId, e.getClass().getSimpleName());
            return Snapshot.EMPTY;
        }
    }

    public void afterScan(long userId, long repoId, String repoName, Snapshot before) {
        try {
            UserSettings settings = settingsRepository.findByUserId(userId).orElse(null);
            if (settings == null) {
                return;
            }
            Snapshot after = snapshot(repoId);
            Integer threshold = settings.getAlertMaxOpen();
            if (threshold != null && before.openDebts() <= threshold && after.openDebts() > threshold) {
                notifications.createNotification(userId, "Debt threshold exceeded",
                        repoName + ": " + after.openDebts() + " open (threshold " + threshold + ")", "WARNING");
                channels.deliver(userId, Purpose.ALERT,
                        messages.threshold(repoName, repoId, after.openDebts(), threshold));
            }
            int newSecrets = after.secrets() - before.secrets();
            if (!Boolean.FALSE.equals(settings.getAlertOnSecret()) && newSecrets > 0) {
                notifications.createNotification(userId, "Leaked secret found",
                        repoName + ": " + newSecrets + " comment(s) contain a secret; revoke and rotate the key", "ERROR");
                channels.deliver(userId, Purpose.ALERT, messages.secrets(repoName, repoId, newSecrets));
            }
        } catch (RuntimeException e) {
            log.warn("Scan alerts failed for repo {}: {}", repoId, e.getClass().getSimpleName());
        }
    }
}
