package dev.hashmark.report.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.report.dto.DebtInsights;
import dev.hashmark.report.dto.HotspotResponse;
import dev.hashmark.report.dto.RepoInsightsRow;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.repository.ReportRepository;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final RepoRepository repoRepository;

    public ReportService(ReportRepository reportRepository, UserSettingsRepository userSettingsRepository,
                         RepoRepository repoRepository) {
        this.reportRepository = reportRepository;
        this.userSettingsRepository = userSettingsRepository;
        this.repoRepository = repoRepository;
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
        UserSettings settings = settings(userId);
        return reportRepository.getInsights(userId, repoId).build(rate(settings), currency(settings));
    }

    /** İzlenen her depo, borcu olmasa da (sıfır içgörüyle); sıralama arayüzde yapılır. */
    public List<RepoInsightsRow> getRepoComparison(Long userId) {
        UserSettings settings = settings(userId);
        Map<Long, DebtInsightsCalculator> perRepo = reportRepository.getInsightsPerRepo(userId);
        return repoRepository.findAllByUserId(userId).stream()
                .map(repo -> row(repo, perRepo.getOrDefault(repo.getId(), new DebtInsightsCalculator())
                        .build(rate(settings), currency(settings))))
                .toList();
    }

    public HotspotResponse getHotspots(Long userId, Long repoId, String rawPath) {
        if (repoRepository.findByIdAndUserId(repoId, userId).isEmpty()) {
            throw ApiException.notFound("Repository not found");
        }
        String path = HotspotCalculator.normalizePath(rawPath);
        String prefix = path.isEmpty() ? "" : path + "/";
        List<ReportRepository.HotspotRow> rows = reportRepository.getHotspotRows(userId, repoId, prefix);
        boolean truncated = rows.size() > ReportRepository.MAX_HOTSPOT_ROWS;
        if (truncated) {
            rows = rows.subList(0, ReportRepository.MAX_HOTSPOT_ROWS);
        }
        return HotspotCalculator.build(path, rows, truncated);
    }

    private static RepoInsightsRow row(Repo repo, DebtInsights insights) {
        return new RepoInsightsRow(repo.getId(), repo.getFullName(), repo.getLastScannedAt(), insights);
    }

    private UserSettings settings(Long userId) {
        return userSettingsRepository.findByUserId(userId).orElse(null);
    }

    private static BigDecimal rate(UserSettings settings) {
        return settings != null ? settings.getHourlyRate() : null;
    }

    private static String currency(UserSettings settings) {
        return settings != null ? settings.getCurrency() : null;
    }
}
