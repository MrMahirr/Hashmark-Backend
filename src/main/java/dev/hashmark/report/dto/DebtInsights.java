package dev.hashmark.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Açık borçların (NOTE/DOC/INFO hariç) yaş, öncelik, sır ve maliyet özeti.
 * Yaş = git blame tarihi, yoksa ilk görülme ({@code olderThanDays} süzgeciyle aynı tanım).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtInsights {

    /** Yaş aralığı: {@code minDays <= yaş < maxDays}; {@code maxDays} null ise üst sınır yok. */
    public record AgeBucket(String key, int minDays, Integer maxDays, int count) {
    }

    private int openDebts;
    private List<AgeBucket> ageBuckets;
    /** 90 gün ve daha eski ("çürüyen") borçlar. */
    private int staleCount;
    /** 0–100: açık borçların ortalama yaşının bir yıla oranı (bir yıldan eski borç tam puan sayılır). */
    private int rotScore;
    private int averageAgeDays;

    /** CRITICAL, HIGH, MEDIUM, LOW, NONE → sayı */
    private Map<String, Integer> priorityCounts;
    /** Önceliği anahtar kelimeden çıkarılanlar. */
    private int autoPriorityCount;
    /** Yorumunda sır bulunup maskelenen açık kayıtlar (NOTE/DOC/INFO dahil). */
    private int secretCount;

    /** Sağlık skorunun girdisi: etiket ağırlığı × öncelik çarpanı toplamı. */
    private double weightedDebt;
    private double estimatedHours;
    /** Kullanıcı saatlik ücret girmediyse null (maliyet de null). */
    private BigDecimal hourlyRate;
    private String currency;
    private BigDecimal estimatedCost;
}
