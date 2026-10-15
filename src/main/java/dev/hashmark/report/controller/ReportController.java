package dev.hashmark.report.controller;

import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.service.EmailService;
import dev.hashmark.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/report")
@Tag(name = "Report")
public class ReportController {

    private final ReportService reportService;
    private final EmailService emailService;

    public ReportController(ReportService reportService, EmailService emailService) {
        this.reportService = reportService;
        this.emailService = emailService;
    }

    @GetMapping("/summary")
    @Operation(summary = "Dashboard ozet ve trend verisi")
    public ResponseEntity<SummaryResponse> getSummary(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long repoId
    ) {
        return ResponseEntity.ok(reportService.getSummary(userId, repoId));
    }

    @PostMapping("/send-test")
    @Operation(summary = "Test e-postasi gonder")
    public ResponseEntity<Map<String, Object>> sendTestEmail(@AuthenticationPrincipal Long userId) {
        boolean configured = emailService.isConfigured();
        boolean sent = configured && emailService.sendTestEmail(userId);
        return ResponseEntity.ok(Map.of("configured", configured, "sent", sent));
    }
}
