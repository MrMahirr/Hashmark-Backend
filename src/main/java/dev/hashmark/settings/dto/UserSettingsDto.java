package dev.hashmark.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * GET/PUT /settings. PUT'ta gönderilmeyen (null) alanlar değişmez;
 * {@code digestLabels} boş liste = tüm etiketler, {@code digestEmail} boş string = GitHub e-postası,
 * {@code hourlyRate} 0 = ücret kaldırılır, {@code alertMaxOpen} 0 = eşik uyarısı kapalı.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettingsDto {
    private Boolean emailNotify;
    private String notifyDay;
    private List<String> digestLabels;
    private String digestEmail;
    /** Saatlik ücret; 0 = kaldır (maliyet gösterilmez). */
    private BigDecimal hourlyRate;
    /** ISO 4217 para birimi; bkz. {@code UserSettingsService.CURRENCIES}. */
    private String currency;
    /** DAILY | WEEKLY | MONTHLY */
    private String digestFrequency;
    /** 0–23 */
    private Integer digestHour;
    /** IANA saat dilimi */
    private String digestTimezone;
    /** Açık borç eşiği; 0 = uyarı kapalı. */
    private Integer alertMaxOpen;
    private Boolean alertOnSecret;
}
