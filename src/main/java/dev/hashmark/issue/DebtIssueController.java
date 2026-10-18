package dev.hashmark.issue;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/debts")
@Tag(name = "Debts", description = "Debt Management")
@SecurityRequirement(name = "bearerAuth")
public class DebtIssueController {

    private final DebtIssueService issues;

    public DebtIssueController(DebtIssueService issues) {
        this.issues = issues;
    }

    @PostMapping("/{id}/issue")
    @Operation(summary = "Borctan GitHub issue olustur (zaten varsa mevcut issue doner; issue kapaninca borc cozulur)")
    public ResponseEntity<DebtIssueService.IssueResult> createIssue(@AuthenticationPrincipal Long userId,
                                                                   @PathVariable Long id) {
        DebtIssueService.IssueResult result = issues.createForDebt(userId, id);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @PostMapping("/issues")
    @Operation(summary = "Secilen borclardan toplu GitHub issue olustur (en fazla 20; sonuc borc basina)")
    public List<DebtIssueService.IssueResult> createIssues(@AuthenticationPrincipal Long userId,
                                                           @RequestBody BulkIssueRequest request) {
        return issues.createForDebts(userId, request == null ? null : request.debtIds());
    }

    public record BulkIssueRequest(List<Long> debtIds) {
    }
}
