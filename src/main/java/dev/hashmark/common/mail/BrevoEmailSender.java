package dev.hashmark.common.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Brevo (eski adıyla Sendinblue) işlemsel e-posta API'si.
 * Belge: POST https://api.brevo.com/v3/smtp/email, başlık {@code api-key}.
 * Yapılandırma: {@code BREVO_API_KEY}, {@code BREVO_SENDER_EMAIL}, {@code BREVO_SENDER_NAME}.
 */
@Component
public class BrevoEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(BrevoEmailSender.class);

    private final RestTemplate restTemplate;
    private final String apiKey;
    private final String senderEmail;
    private final String senderName;
    private final String apiUrl;

    public BrevoEmailSender(RestTemplate restTemplate,
                            @Value("${brevo.api-key:}") String apiKey,
                            @Value("${brevo.sender-email:noreply@hashmark.dev}") String senderEmail,
                            @Value("${brevo.sender-name:Hashmark}") String senderName,
                            @Value("${brevo.api-url:https://api.brevo.com/v3/smtp/email}") String apiUrl) {
        this.restTemplate = restTemplate;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.senderEmail = senderEmail;
        this.senderName = senderName;
        this.apiUrl = apiUrl;
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isEmpty() && !"placeholder".equalsIgnoreCase(apiKey);
    }

    @Override
    public boolean send(String toEmail, String toName, String subject, String htmlContent) {
        if (toEmail == null || toEmail.isBlank()) {
            log.warn("Skipping email: recipient address is empty. subject={}", subject);
            return false;
        }
        if (!isConfigured()) {
            log.info("Skipping email because Brevo API key is not configured. subject={}", subject);
            return false;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("api-key", apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        Map<String, Object> to = new LinkedHashMap<>();
        to.put("email", toEmail);
        if (toName != null && !toName.isBlank()) {
            to.put("name", toName);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", Map.of("email", senderEmail, "name", senderName));
        body.put("to", List.of(to));
        body.put("subject", subject);
        body.put("htmlContent", htmlContent);

        try {
            restTemplate.postForEntity(apiUrl, new HttpEntity<>(body, headers), String.class);
            log.info("Email sent via Brevo. subject={}", subject);
            return true;
        } catch (Exception e) {
            log.error("Failed to send email via Brevo. subject={}", subject, e);
            return false;
        }
    }
}
