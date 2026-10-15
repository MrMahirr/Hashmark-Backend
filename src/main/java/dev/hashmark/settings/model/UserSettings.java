package dev.hashmark.settings.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserSettings {
    private Long id;
    private Long userId;
    private Boolean emailNotify;
    private String notifyDay;
    /** Virgülle ayrılmış etiketler; {@code null} = hepsi. */
    private String digestLabels;
    /** Özetin gönderileceği alternatif adres; {@code null} = GitHub e-postası. */
    private String digestEmail;
    private LocalDate lastDigestSentOn;
    private LocalDateTime createdAt;

    /** Dahil edilecek etiketler; boş küme = hepsi. */
    public Set<String> labelSet() {
        if (digestLabels == null || digestLabels.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(digestLabels.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }
}
