package dev.hashmark.webhook;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Webhook bakımı (saatlik, dağıtık kilitli):
 * <ul>
 *   <li>Eski sürüm hook'lara yeni olayları ekler (V15'te yalnızca push; şimdi push + pull_request + issues).
 *       Kullanıcı hiçbir şey yapmadan PR kontrolü devreye girer. Yetki kaybı vb. hatada sonraki saat yeniden denenir.</li>
 *   <li>7 günden eski teslimat kimliklerini siler (GitHub yeniden gönderimleri bu süreden çok kısadır).</li>
 * </ul>
 */
@Component
public class WebhookMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(WebhookMaintenanceJob.class);
    static final int BATCH = 50;
    static final int DELIVERY_RETENTION_DAYS = 7;

    private final RepoRepository repos;
    private final UserRepository users;
    private final AesEncryptionUtil aes;
    private final GitHubHookClient hooks;
    private final WebhookDeliveryRepository deliveries;

    public WebhookMaintenanceJob(RepoRepository repos, UserRepository users, AesEncryptionUtil aes,
                                 GitHubHookClient hooks, WebhookDeliveryRepository deliveries) {
        this.repos = repos;
        this.users = users;
        this.aes = aes;
        this.hooks = hooks;
        this.deliveries = deliveries;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    @SchedulerLock(name = "webhook-maintenance", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void run() {
        // Kullanıcılar arası iş (RLS, V11)
        TenantContext.asSystem(() -> {
            upgradeOutdatedHooks();
            purgeDeliveries();
        });
    }

    /** @return yükseltilen hook sayısı */
    int upgradeOutdatedHooks() {
        int upgraded = 0;
        List<Repo> outdated;
        try {
            outdated = repos.findOutdatedWebhooks(GitHubHookClient.EVENTS_VERSION, BATCH);
        } catch (Exception e) {
            log.warn("Could not list outdated webhooks: {}", e.getMessage());
            return 0;
        }
        for (Repo repo : outdated) {
            try {
                User user = users.findById(repo.getUserId()).orElse(null);
                if (user == null || user.getGithubToken() == null || user.getGithubToken().isBlank()) {
                    continue; // bağlantı kesilmiş: hook zaten temizlenmiş olmalı
                }
                String token = aes.decrypt(user.getGithubToken());
                if (hooks.addEvents(repo.getFullName(), token, repo.getWebhookId())) {
                    repos.updateWebhookVersion(repo.getId(), GitHubHookClient.EVENTS_VERSION);
                    upgraded++;
                } else {
                    log.warn("Webhook {} of repo {} no longer exists on GitHub", repo.getWebhookId(), repo.getId());
                }
            } catch (Exception e) {
                log.warn("Could not upgrade webhook of repo {}: {}", repo.getId(), e.getMessage());
            }
        }
        if (upgraded > 0) {
            log.info("Upgraded {} webhook(s) to event set v{}", upgraded, GitHubHookClient.EVENTS_VERSION);
        }
        return upgraded;
    }

    private void purgeDeliveries() {
        try {
            int deleted;
            do {
                deleted = deliveries.deleteOlderThanDays(DELIVERY_RETENTION_DAYS, 5_000);
            } while (deleted == 5_000);
        } catch (Exception e) {
            log.warn("Could not purge webhook deliveries: {}", e.getMessage());
        }
    }
}
