package dev.hashmark.scanner.controller;

import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/scan")
@Tag(name = "Scan", description = "Scanner Management")
@SecurityRequirement(name = "bearerAuth")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping("/{repoId}")
    @Operation(summary = "Manuel tarama baslat")
    public ResponseEntity<Map<String, Object>> startScan(@AuthenticationPrincipal Long userId, @PathVariable Long repoId) {
        Map<String, Object> result = scanService.startScan(repoId, userId);
        return ResponseEntity.accepted().body(result);
    }

    @GetMapping("/{repoId}/status")
    @Operation(summary = "Tarama durumunu sorgula")
    public ScanJob getScanStatus(@AuthenticationPrincipal Long userId, @PathVariable Long repoId) {
        return scanService.getScanStatus(repoId, userId);
    }
}
