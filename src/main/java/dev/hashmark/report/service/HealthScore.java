package dev.hashmark.report.service;

/**
 * Sağlık skoru (0–100) ve harf notu, ağırlıklı borçtan. Arayüzdeki {@code features/dashboard/utils/health.ts}
 * ile aynı formül; rozet, sohbet özeti ve panel aynı notu göstersin diye ikisi birlikte değişmeli.
 */
public record HealthScore(int score, String grade) {

    public static HealthScore of(double weightedDebt) {
        double count = Double.isFinite(weightedDebt) ? Math.max(0, weightedDebt) : 0;
        int score = (int) Math.max(0, Math.min(100, 100 - Math.floor(count * 1.5)));
        String grade = count > 80 ? "F" : count > 50 ? "D" : count > 30 ? "C" : count > 10 ? "B" : "A";
        return new HealthScore(score, grade);
    }
}
