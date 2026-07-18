package dev.hashmark.report.controller;

import dev.hashmark.auth.model.User;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.service.EmailService;
import dev.hashmark.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private Long getCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long) {
            return (Long) auth.getPrincipal();
        } else if (auth != null && auth.getPrincipal() instanceof String) {
            return Long.parseLong((String) auth.getPrincipal());
        }
        throw new RuntimeException("Unauthorized");
    }

    @GetMapping("/summary")
    @Operation(summary = "Dashboard ozet ve trend verisi")
    public ResponseEntity<SummaryResponse> getSummary(@RequestParam(required = false) Long repoId) {
        Long userId = getCurrentUserId();
        return ResponseEntity.ok(reportService.getSummary(userId, repoId));
    }

    @PostMapping("/send-test")
    @Operation(summary = "Test e-postasi gonder")
    public ResponseEntity<Void> sendTestEmail() {
        Long userId = getCurrentUserId();
        emailService.sendTestEmail(userId, null);
        return ResponseEntity.ok().build();
    }
}
