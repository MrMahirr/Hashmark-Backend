package dev.hashmark.report.scheduler;

import dev.hashmark.auth.model.User;
import dev.hashmark.report.service.EmailService;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WeeklyReportScheduler {

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
                System.err.println("Failed to send weekly report for user " + user.getId() + ": " + e.getMessage());
            }
        }
    }
}
