package dev.hashmark.report.service;

import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.repository.ReportRepository;
import org.springframework.stereotype.Service;

@Service
public class ReportService {

    private final ReportRepository reportRepository;

    public ReportService(ReportRepository reportRepository) {
        this.reportRepository = reportRepository;
    }

    public SummaryResponse getSummary(Long userId, Long repoId) {
        return SummaryResponse.builder()
                .trendData(reportRepository.getTrendData(userId, repoId))
                .labelStats(reportRepository.getLabelStats(userId, repoId))
                .topModules(reportRepository.getTopModules(userId, repoId))
                .build();
    }
}
