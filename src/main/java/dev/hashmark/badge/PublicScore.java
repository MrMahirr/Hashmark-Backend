package dev.hashmark.badge;

import dev.hashmark.report.dto.DebtInsights;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Herkese açık "debt score" sayfasının verisi. Bilinçli olarak dar: not, sayılar ve dağılımlar. Borç içeriği, dosya
 * yolu, yazar, öncelik, sızan sır sayısı ve maliyet <b>yoktur</b> (sırrın varlığı bile saldırgana ipucu olur).
 *
 * @param labels yalnızca borç etiketleri (TODO/FIXME/HACK/XXX + özel), açık sayıları
 * @param trend  son 8 hafta, hafta başına açık borç
 */
public record PublicScore(String repoName, boolean privateRepo, LocalDateTime lastScannedAt, String grade, int score,
                          int openDebts, int staleCount, int rotScore, int averageAgeDays, Map<String, Integer> labels,
                          List<DebtInsights.AgeBucket> ageBuckets, List<Week> trend) {

    public record Week(LocalDate weekStart, int total) {
    }
}
