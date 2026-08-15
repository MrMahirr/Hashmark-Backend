package dev.hashmark.debt.controller;

import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.dto.DebtStatsDto;
import dev.hashmark.debt.dto.PageResponse;
import dev.hashmark.debt.service.DebtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/debts")
@Tag(name = "Debts", description = "Debt Management")
@SecurityRequirement(name = "bearerAuth")
public class DebtController {

    private final DebtService debtService;

    public DebtController(DebtService debtService) {
        this.debtService = debtService;
    }

    @GetMapping
    @Operation(summary = "Borclari listele (filtreli, sayfali)")
    public PageResponse<DebtDto> listDebts(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long repoId,
            @RequestParam(required = false) String label,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        DebtFilterRequest filter = DebtFilterRequest.builder()
                .repoId(repoId)
                .label(label)
                .status(status)
                .page(page)
                .size(size)
                .build();
        return debtService.listDebts(userId, filter);
    }

    @GetMapping("/stats")
    @Operation(summary = "Borc istatistikleri")
    public DebtStatsDto getStats(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long repoId
    ) {
        return debtService.getStats(userId, repoId);
    }
}
