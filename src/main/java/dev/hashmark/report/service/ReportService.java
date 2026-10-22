package dev.hashmark.report.service;

import dev.hashmark.report.dto.DebtInsights;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.repository.ReportRepository;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final UserSettingsRepository userSettingsRepository;

    public ReportService(ReportRepository reportRepository, UserSettingsRepository userSettingsRepository) {
        this.reportRepository = reportRepository;
        this.userSettingsRepository = userSettingsRepository;
    }

    public SummaryResponse getSummary(Long userId, Long repoId) {
        return SummaryResponse.builder()
                .trendData(reportRepository.getTrendData(userId, repoId))
                .labelStats(reportRepository.getLabelStats(userId, repoId))
                .topModules(reportRepository.getTopModules(userId, repoId))
                .insights(getInsights(userId, repoId))
                .build();
    }

    public DebtInsights getInsights(Long userId, Long repoId) {
        UserSettings settings = userSettingsRepository.findByUserId(userId).orElse(null);
        return reportRepository.getInsights(userId, repoId).build(
                settings != null ? settings.getHourlyRate() : null,
                settings != null ? settings.getCurrency() : null);
    }
}
