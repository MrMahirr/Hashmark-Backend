package dev.hashmark.report.scheduler;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.report.service.EmailService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class WeeklyReportScheduler {

    private final EmailService emailService;
    private final UserRepository userRepository;

    public WeeklyReportScheduler(EmailService emailService, UserRepository userRepository) {
        this.emailService = emailService;
        this.userRepository = userRepository;
    }

    @Scheduled(cron = "0 0 8 * * MON")
    public void sendWeeklyReports() {
        // Fetch all users for now since UserSettingsRepository is not yet implemented (Step 9).
        // In the future, this should filter by email_notify = true
        List<User> users = userRepository.findAll();
        for (User user : users) {
            try {
                emailService.sendWeeklyReport(user);
            } catch (Exception e) {
                System.err.println("Failed to send weekly report for user " + user.getId() + ": " + e.getMessage());
            }
        }
    }
}
