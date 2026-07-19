package dev.hashmark.report.scheduler;

import dev.hashmark.auth.model.User;
import dev.hashmark.report.service.EmailService;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WeeklyReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReportScheduler.class);

    private final EmailService emailService;
    private final UserSettingsRepository userSettingsRepository;

    public WeeklyReportScheduler(EmailService emailService, UserSettingsRepository userSettingsRepository) {
        this.emailService = emailService;
        this.userSettingsRepository = userSettingsRepository;
    }

    @Scheduled(cron = "0 0 8 * * MON")
    public void sendWeeklyReports() {
        List<User> users = userSettingsRepository.findUsersWithNotifyEnabled();
        for (User user : users) {
            try {
                emailService.sendWeeklyReport(user);
            } catch (Exception e) {
                log.error("Failed to send weekly report. userId={}", user.getId(), e);
            }
        }
    }
}
