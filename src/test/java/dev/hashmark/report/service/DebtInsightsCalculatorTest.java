package dev.hashmark.report.service;

import dev.hashmark.report.dto.DebtInsights;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DebtInsightsCalculatorTest {

    @Test
    void emptyInputGivesZeroes() {
        DebtInsights insights = new DebtInsightsCalculator().build(null, null);

        assertEquals(0, insights.getOpenDebts());
        assertEquals(0, insights.getRotScore());
        assertEquals(0, insights.getStaleCount());
        assertEquals(5, insights.getAgeBuckets().size());
        assertEquals(0.0, insights.getWeightedDebt());
        assertNull(insights.getEstimatedCost());
    }

    @Test
    void bucketsMatchFilterBoundaries() {
        assertEquals(0, DebtInsightsCalculator.bucketOf(0));
        assertEquals(0, DebtInsightsCalculator.bucketOf(29.9));
        assertEquals(1, DebtInsightsCalculator.bucketOf(30));
        assertEquals(2, DebtInsightsCalculator.bucketOf(90));
        assertEquals(3, DebtInsightsCalculator.bucketOf(364));
        assertEquals(4, DebtInsightsCalculator.bucketOf(5000));
    }

    @Test
    void aggregatesAgeWeightPriorityAndCost() {
        DebtInsights insights = new DebtInsightsCalculator()
                // 2 yeni TODO (10 gün), öncelik yok
                .add("TODO", null, 0, 2, 0, 20, 20)
                // 1 kritik FIXME, 200 gün
                .add("FIXME", "CRITICAL", 3, 1, 1, 200, 200)
                // 1 HACK, 2 yıllık (çürüme bir yılla sınırlı)
                .add("HACK", "LOW", 4, 1, 0, 365, 730)
                .secretCount(1)
                .build(new BigDecimal("50.00"), "TRY");

        assertEquals(4, insights.getOpenDebts());
        assertEquals(List.of(2, 0, 0, 1, 1),
                insights.getAgeBuckets().stream().map(DebtInsights.AgeBucket::count).toList());
        assertEquals("90-180", insights.getAgeBuckets().get(2).key());
        assertEquals("365+", insights.getAgeBuckets().get(4).key());
        assertNull(insights.getAgeBuckets().get(4).maxDays());
        assertEquals(2, insights.getStaleCount());
        // (20 + 200 + 365) / (365 × 4) = %40
        assertEquals(40, insights.getRotScore());
        assertEquals(238, insights.getAverageAgeDays());

        assertEquals(2, insights.getPriorityCounts().get("NONE"));
        assertEquals(1, insights.getPriorityCounts().get("CRITICAL"));
        assertEquals(1, insights.getPriorityCounts().get("LOW"));
        assertEquals(1, insights.getAutoPriorityCount());
        assertEquals(1, insights.getSecretCount());

        // 2×1×1 + 1×2×2 + 1×2×0.75 = 7.5
        assertEquals(7.5, insights.getWeightedDebt());
        // 2×2 + 4 + 6 = 14 saat × 50
        assertEquals(14.0, insights.getEstimatedHours());
        assertEquals(new BigDecimal("700"), insights.getEstimatedCost());
        assertEquals("TRY", insights.getCurrency());
    }

    @Test
    void unknownPriorityCountsAsNone() {
        DebtInsights insights = new DebtInsightsCalculator().add("TODO", "weird", 0, 3, 0, 0, 0).build(null, "USD");

        assertEquals(3, insights.getPriorityCounts().get("NONE"));
        assertNull(insights.getCurrency());
    }
}
