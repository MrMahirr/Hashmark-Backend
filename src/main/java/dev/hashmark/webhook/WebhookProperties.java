package dev.hashmark.webhook;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "webhooks")
public class WebhookProperties {

    /**
     * GitHub'ın ulaşabileceği API kökü (ör. {@code https://hashmark.example.com/api/v1}). Boşsa otomatik tarama
     * açılamaz; hook adresi {@code <publicUrl>/webhooks/github/<repoId>} olur.
     */
    private String publicUrl = "";

    /** Kabul edilen en büyük teslimat gövdesi; push yükü GitHub'da 20 commit ile sınırlıdır. */
    private int maxPayloadBytes = 5 * 1024 * 1024;

    public boolean isEnabled() {
        return publicUrl != null && !publicUrl.isBlank();
    }

    public String hookUrl(long repoId) {
        String base = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        return base + "/webhooks/github/" + repoId;
    }
}
