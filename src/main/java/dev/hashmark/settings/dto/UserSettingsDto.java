package dev.hashmark.settings.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GET/PUT /settings. PUT'ta gönderilmeyen (null) alanlar değişmez;
 * {@code digestLabels} boş liste = tüm etiketler, {@code digestEmail} boş string = GitHub e-postası.
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
}
