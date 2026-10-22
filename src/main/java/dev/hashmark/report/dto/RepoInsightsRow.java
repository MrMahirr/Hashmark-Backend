package dev.hashmark.report.dto;

import java.time.LocalDateTime;

/** Depo karşılaştırma satırı: izlenen bir depo ve içgörüleri (hiç taranmadıysa {@code lastScannedAt} null). */
public record RepoInsightsRow(Long repoId, String fullName, LocalDateTime lastScannedAt, DebtInsights insights) {
}
