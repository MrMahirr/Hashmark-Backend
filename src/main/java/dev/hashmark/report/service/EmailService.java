package dev.hashmark.report.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.report.dto.SummaryResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class EmailService {

    @Value("${resend.api-key:placeholder}")
    private String resendApiKey;

    @Value("${resend.from-email:noreply@hashmark.dev}")
    private String fromEmail;

    private final RestTemplate restTemplate;
    private final ReportService reportService;

    public EmailService(RestTemplate restTemplate, ReportService reportService) {
        this.restTemplate = restTemplate;
        this.reportService = reportService;
    }

    public void sendWeeklyReport(User user) {
        SummaryResponse summary = reportService.getSummary(user.getId(), null);

        int totalDebts = summary.getTrendData().isEmpty() ? 0 : summary.getTrendData().get(summary.getTrendData().size() - 1).getTotalDebts();
        
        if (totalDebts == 0 && summary.getLabelStats() != null) {
            totalDebts = summary.getLabelStats().getTodoCount() + summary.getLabelStats().getFixmeCount() +
                         summary.getLabelStats().getHackCount() + summary.getLabelStats().getXxxCount();
        }

        String topModuleHtml = "";
        if (summary.getTopModules() != null && !summary.getTopModules().isEmpty()) {
            topModuleHtml = "<p>Worst Module: " + summary.getTopModules().get(0).getModulePath() + 
                            " (" + summary.getTopModules().get(0).getDebtCount() + " debts)</p>";
        }

        String htmlContent = "<html><body>" +
                "<h2>Hashmark Weekly Report for " + user.getName() + "</h2>" +
                "<p>Here is your debt summary across all your connected repositories:</p>" +
                "<table border='1' cellpadding='5'>" +
                "<tr><th>Total Open Debts</th><td>" + totalDebts + "</td></tr>" +
                "</table>" +
                topModuleHtml +
                "</body></html>";

        sendEmail(user.getEmail(), "Hashmark Weekly Report", htmlContent);
    }

    public void sendTestEmail(Long userId, User user) {
        if (user == null) {
            user = User.builder().id(userId).email("test@hashmark.dev").name("Test User").build();
        }
        sendWeeklyReport(user);
    }

    private void sendEmail(String to, String subject, String html) {
        if ("placeholder".equals(resendApiKey) || resendApiKey == null || resendApiKey.isEmpty()) {
            System.out.println("Mock sending email to: " + to + " with subject: " + subject);
            return;
        }

        String url = "https://api.resend.com/emails";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + resendApiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("from", fromEmail);
        body.put("to", new String[]{to});
        body.put("subject", subject);
        body.put("html", html);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        try {
            restTemplate.postForEntity(url, request, String.class);
            System.out.println("Email sent successfully to " + to);
        } catch (Exception e) {
            System.err.println("Failed to send email to " + to + ": " + e.getMessage());
        }
    }
}
