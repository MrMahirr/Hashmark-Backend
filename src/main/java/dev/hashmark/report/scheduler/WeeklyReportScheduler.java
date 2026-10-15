package dev.hashmark.report.scheduler;

import dev.hashmark.auth.model.User;
import dev.hashmark.report.service.EmailService;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Her gün 08:00'de (varsayılan Europe/Istanbul) o gün özet isteyen kullanıcılara haftalık raporu gönderir.
 * Aynı gün ikinci kez gönderilmez (son gönderim günü kaydedilir).
 */
@Component
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private final EmailService emailService;
    private final UserSettingsRepository userSettingsRepository;
    private final ZoneId zone;

    public WeeklyReportScheduler(EmailService emailService, UserSettingsRepository userSettingsRepository,
                                 @Value("${report.timezone:Europe/Istanbul}") String timezone) {
        this.emailService = emailService;
        this.userSettingsRepository = userSettingsRepository;
        this.zone = ZoneId.of(timezone);
    }

    @Scheduled(cron = "0 0 8 * * *", zone = "${report.timezone:Europe/Istanbul}")
    public void sendWeeklyReports() {
        LocalDate today = LocalDate.now(zone);
        sendFor(today.getDayOfWeek(), today);
    }

    /** Test edilebilirlik için gün dışarıdan verilebilir. */
    void sendFor(DayOfWeek day, LocalDate today) {
        if (!emailService.isConfigured()) {
            log.info("Weekly reports skipped: email provider is not configured");
            return;
        }
        List<UserSettingsRepository.DigestRecipient> recipients =
                userSettingsRepository.findDigestRecipients(day.name(), today);
        for (UserSettingsRepository.DigestRecipient recipient : recipients) {
            User user = recipient.user();
            UserSettings settings = recipient.settings();
            try {
                if (emailService.sendWeeklyReport(user, settings)) {
                    userSettingsRepository.markDigestSent(user.getId(), today);
                }
            } catch (Exception e) {
                log.error("Failed to send weekly report. userId={}", user.getId(), e);
            }
        }
    }
}
