package dev.hashmark.debt.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.dto.DebtStatsDto;
import dev.hashmark.debt.dto.DebtTriageRequest;
import dev.hashmark.debt.dto.PageResponse;
import dev.hashmark.debt.repository.DebtRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DebtService {

    public static final int MAX_EXPORT_ROWS = 50_000;
    private static final Set<String> TRIAGE_STATUSES = Set.of("OPEN", "IGNORED", "WONT_FIX", "RESOLVED");
    private static final int MAX_ASSIGNEE_LENGTH = 100;

    private final DebtRepository debtRepository;
    private final DebtExportService exportService;

    public DebtService(DebtRepository debtRepository, DebtExportService exportService) {
        this.debtRepository = debtRepository;
        this.exportService = exportService;
    }

    public PageResponse<DebtDto> listDebts(Long userId, DebtFilterRequest filter) {
        return debtRepository.findByFilter(userId, filter);
    }

    public DebtStatsDto getStats(Long userId, Long repoId) {
        return debtRepository.getStats(userId, repoId);
    }

    public DebtDto triage(Long userId, Long debtId, DebtTriageRequest request) {
        String status = null;
        if (request.getStatus() != null) {
            status = request.getStatus().trim().toUpperCase(Locale.ROOT);
            if (!TRIAGE_STATUSES.contains(status)) {
                throw ApiException.badRequest("status must be one of OPEN, IGNORED, WONT_FIX, RESOLVED");
            }
        }
        String assignee = request.getAssignee();
        if (assignee != null && assignee.trim().length() > MAX_ASSIGNEE_LENGTH) {
            throw ApiException.badRequest("assignee is too long");
        }
        return debtRepository.updateTriage(userId, debtId, status, assignee)
                .orElseThrow(() -> ApiException.notFound("Debt not found"));
    }

    public DebtExportService.ExportFile export(Long userId, DebtFilterRequest filter, String format) {
        List<DebtDto> debts = debtRepository.findForExport(userId, filter, MAX_EXPORT_ROWS);
        return exportService.export(debts, format);
    }
}
