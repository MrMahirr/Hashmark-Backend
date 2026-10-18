package dev.hashmark.debt.controller;

import dev.hashmark.debt.dto.DebtContextDto;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.DebtFilterRequest;
import dev.hashmark.debt.dto.DebtStatsDto;
import dev.hashmark.debt.dto.DebtTriageRequest;
import dev.hashmark.debt.dto.PageResponse;
import dev.hashmark.debt.service.DebtContextService;
import dev.hashmark.debt.service.DebtExportService.ExportFile;
import dev.hashmark.debt.service.DebtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/debts")
@Tag(name = "Debts", description = "Debt Management")
@SecurityRequirement(name = "bearerAuth")
public class DebtController {

    private final DebtService debtService;
    private final DebtContextService debtContextService;

    public DebtController(DebtService debtService, DebtContextService debtContextService) {
        this.debtService = debtService;
        this.debtContextService = debtContextService;
    }

    @GetMapping
    @Operation(summary = "Borclari listele (filtreli, aramali, siralamali, sayfali; size en fazla 100)")
    public PageResponse<DebtDto> listDebts(@AuthenticationPrincipal Long userId, DebtFilterRequest filter) {
        return debtService.listDebts(userId, filter);
    }

    @GetMapping("/authors")
    @Operation(summary = "Acik borclari ekleyen GitHub kullanicilari (git blame; yazar filtresi icin)")
    public java.util.List<String> getAuthors(@AuthenticationPrincipal Long userId,
                                             @RequestParam(required = false) Long repoId) {
        return debtService.getAuthors(userId, repoId);
    }

    @GetMapping("/stats")
    @Operation(summary = "Borc istatistikleri")
    public DebtStatsDto getStats(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long repoId
    ) {
        return debtService.getStats(userId, repoId);
    }

    @GetMapping("/export")
    @Operation(summary = "Borclari disa aktar (format: csv | json | sarif; ayni filtreler, en fazla 50.000 kayit)")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal Long userId,
            DebtFilterRequest filter,
            @RequestParam(defaultValue = "csv") String format
    ) {
        ExportFile file = debtService.export(userId, filter, format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.filename()).build().toString())
                .body(file.content());
    }

    @GetMapping("/{id}/context")
    @Operation(summary = "Borcun cevresindeki kaynak kodu (±radius satir, 1-30; varsayilan 5)")
    public DebtContextDto getContext(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @RequestParam(required = false) Integer radius
    ) {
        return debtContextService.getContext(userId, id, radius);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Borcu yok say / won't fix / yeniden ac ve kisiye ata")
    public DebtDto triage(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @RequestBody DebtTriageRequest request
    ) {
        return debtService.triage(userId, id, request);
    }
}
