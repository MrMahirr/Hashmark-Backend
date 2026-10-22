package dev.hashmark.report.scheduler;

import dev.hashmark.consent.ConsentService;
import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.auth.model.User;
import dev.hashmark.report.service.DigestService;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Her saat başı, vadesi gelen özetleri gönderir (e-posta ve sohbet kanalları). Sıklık, gün, saat ve saat dilimi
 * kullanıcı ayarındadır ({@link DigestSchedule}); varsayılan haftalık, 08:00, {@code report.timezone}.
 * Aynı yerel günde ikinci kez gönderilmez (son gönderim günü kaydedilir).
 */
@Component
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private final DigestService digestService;
    private final UserSettingsRepository userSettingsRepository;
    private final ZoneId zone;
    private final ConsentService consentService;

    public WeeklyReportScheduler(DigestService digestService, UserSettingsRepository userSettingsRepository,
                                 ConsentService consentService,
                                 @Value("${report.timezone:Europe/Istanbul}") String timezone) {
        this.digestService = digestService;
        this.userSettingsRepository = userSettingsRepository;
        this.consentService = consentService;
        this.zone = ZoneId.of(timezone);
    }

    @Scheduled(cron = "0 0 * * * *")
    // 2+ instance'ta tek gönderim: kilit en az 5 dk tutulur (saat kayması olan ikinci instance da atlar)
    @SchedulerLock(name = "weekly-report", lockAtMostFor = "PT50M", lockAtLeastFor = "PT5M")
    public void sendDueDigests() {
        sendDue(Instant.now());
    }

    /** Test edilebilirlik için an dışarıdan verilebilir. */
    void sendDue(Instant now) {
        // Dünyadaki en ileri yerel gün UTC + 1; o günden önce gönderilmiş herkes aday, kesin süzme Java'da
        LocalDate latestLocalDate = now.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        // Alıcı listesi kullanıcılar arası (sistem); her rapor yalnızca o kullanıcının kapsamında üretilir (RLS, V11)
        List<UserSettingsRepository.DigestRecipient> candidates =
                TenantContext.asSystem(() -> userSettingsRepository.findDigestCandidates(latestLocalDate));
        for (UserSettingsRepository.DigestRecipient recipient : candidates) {
            User user = recipient.user();
            UserSettings settings = recipient.settings();
            LocalDate due = DigestSchedule.dueDate(settings, now, zone);
            if (due == null) {
                continue;
            }
            // Onay kapısı açıkken onaysız kullanıcıya özet üretilmez (plan 5.10)
            if (consentService.isProcessingBlocked(user.getId())) {
                continue;
            }
            try {
                TenantContext.asUser(user.getId(), () -> {
                    if (digestService.send(user, settings)) {
                        userSettingsRepository.markDigestSent(user.getId(), due);
                    }
                });
            } catch (Exception e) {
                log.error("Failed to send digest. userId={}", user.getId(), e);
            }
        }
    }
}
