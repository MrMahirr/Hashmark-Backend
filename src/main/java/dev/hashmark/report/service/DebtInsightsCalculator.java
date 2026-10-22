package dev.hashmark.report.service;

import dev.hashmark.report.dto.DebtInsights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Gruplanmış borç satırlarından {@link DebtInsights} üretir. Veritabanı yalnızca
 * (etiket, öncelik, yaş aralığı) başına sayıları verir; ağırlık ve maliyet burada hesaplanır.
 */
public final class DebtInsightsCalculator {

    /** Yaş aralıklarının alt sınırları (gün). Arayüzdeki "N+ gün" süzgeci bu sınırları kullanır. */
    public static final int[] AGE_BOUNDS = {0, 30, 90, 180, 365};
    public static final int STALE_DAYS = 90;
    private static final int ROT_CAP_DAYS = 365;
    private static final Set<String> PRIORITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");

    private final int[] bucketCounts = new int[AGE_BOUNDS.length];
    private final Map<String, Integer> priorityCounts = new LinkedHashMap<>();
    private int open;
    private int autoPriority;
    private int secrets;
    private double cappedAgeDays;
    private double totalAgeDays;
    private double weighted;
    private double hours;

    public DebtInsightsCalculator() {
        for (String p : List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE")) {
            priorityCounts.put(p, 0);
        }
    }

    /** Yaş aralığının indeksi ({@link #AGE_BOUNDS}). */
    public static int bucketOf(double ageDays) {
        int index = 0;
        for (int i = 0; i < AGE_BOUNDS.length; i++) {
            if (ageDays >= AGE_BOUNDS[i]) {
                index = i;
            }
        }
        return index;
    }

    /**
     * @param count         bu gruptaki borç sayısı
     * @param cappedAgeDays grubun bir yılla sınırlanmış yaş toplamı (gün)
     * @param ageDays       grubun yaş toplamı (gün)
     */
    public DebtInsightsCalculator add(String label, String priority, int bucket, int count, int autoPriorityCount,
                                      double cappedAgeDays, double ageDays) {
        if (count <= 0) {
            return this;
        }
        String p = priority == null ? "NONE" : priority.toUpperCase(Locale.ROOT);
        priorityCounts.merge(PRIORITIES.contains(p) ? p : "NONE", count, Integer::sum);
        bucketCounts[Math.max(0, Math.min(bucket, AGE_BOUNDS.length - 1))] += count;
        open += count;
        autoPriority += autoPriorityCount;
        this.cappedAgeDays += cappedAgeDays;
        this.totalAgeDays += ageDays;
        weighted += count * DebtWeights.labelWeight(label) * DebtWeights.priorityFactor(priority);
        hours += count * DebtWeights.labelHours(label);
        return this;
    }

    /** Sızan sırlar bilgi yorumlarında (NOTE) da olabilir; ayrı sayılır. */
    public DebtInsightsCalculator secretCount(int count) {
        secrets = Math.max(0, count);
        return this;
    }

    public DebtInsights build(BigDecimal hourlyRate, String currency) {
        List<DebtInsights.AgeBucket> buckets = new ArrayList<>();
        int stale = 0;
        for (int i = 0; i < AGE_BOUNDS.length; i++) {
            Integer max = i + 1 < AGE_BOUNDS.length ? AGE_BOUNDS[i + 1] : null;
            String key = max == null ? AGE_BOUNDS[i] + "+" : AGE_BOUNDS[i] + "-" + max;
            buckets.add(new DebtInsights.AgeBucket(key, AGE_BOUNDS[i], max, bucketCounts[i]));
            if (AGE_BOUNDS[i] >= STALE_DAYS) {
                stale += bucketCounts[i];
            }
        }

        int rot = open == 0 ? 0 : (int) Math.round(100.0 * cappedAgeDays / ((double) ROT_CAP_DAYS * open));
        BigDecimal roundedHours = BigDecimal.valueOf(hours).setScale(1, RoundingMode.HALF_UP);
        BigDecimal cost = hourlyRate == null ? null
                : hourlyRate.multiply(roundedHours).setScale(0, RoundingMode.HALF_UP);

        return DebtInsights.builder()
                .openDebts(open)
                .ageBuckets(buckets)
                .staleCount(stale)
                .rotScore(Math.max(0, Math.min(100, rot)))
                .averageAgeDays(open == 0 ? 0 : (int) Math.round(totalAgeDays / open))
                .priorityCounts(priorityCounts)
                .autoPriorityCount(autoPriority)
                .secretCount(secrets)
                .weightedDebt(Math.round(weighted * 10) / 10.0)
                .estimatedHours(roundedHours.doubleValue())
                .hourlyRate(hourlyRate)
                .currency(hourlyRate == null ? null : currency)
                .estimatedCost(cost)
                .build();
    }
}
