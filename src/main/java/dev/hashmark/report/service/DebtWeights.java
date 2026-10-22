package dev.hashmark.report.service;

import java.util.Locale;

/**
 * Borç ağırlıkları ve tahmini çözüm süreleri. Sağlık skoru (ağırlıklı borç) ve maliyet bunlardan hesaplanır;
 * arayüzdeki açıklama metinleri de aynı değerleri anlatır, değişirse ikisi birlikte güncellenmeli.
 *
 * <ul>
 *   <li>Etiket ağırlığı: FIXME ve HACK bilinen bir hatayı/kestirmeyi işaret eder, TODO'dan ağırdır.</li>
 *   <li>Öncelik çarpanı: kritik borç iki kat sayılır, düşük öncelikli olanın etkisi azalır.</li>
 *   <li>Saat: etiket başına kaba çözüm süresi tahmini (özel etiketler TODO gibi sayılır).</li>
 * </ul>
 */
public final class DebtWeights {

    private DebtWeights() {
    }

    public static double labelWeight(String label) {
        return switch (normalize(label)) {
            case "FIXME", "HACK" -> 2.0;
            case "XXX" -> 1.5;
            default -> 1.0;
        };
    }

    public static double priorityFactor(String priority) {
        return switch (normalize(priority)) {
            case "CRITICAL" -> 2.0;
            case "HIGH" -> 1.5;
            case "LOW" -> 0.75;
            default -> 1.0;
        };
    }

    public static double labelHours(String label) {
        return switch (normalize(label)) {
            case "FIXME" -> 4.0;
            case "HACK" -> 6.0;
            case "XXX" -> 3.0;
            default -> 2.0;
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
