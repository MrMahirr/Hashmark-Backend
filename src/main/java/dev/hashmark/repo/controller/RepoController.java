package dev.hashmark.repo.controller;

import dev.hashmark.repo.dto.RepoDto;
import dev.hashmark.repo.service.RepoService;
import dev.hashmark.webhook.RepoWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/repos")
@Tag(name = "Repo", description = "Repository Management")
@SecurityRequirement(name = "bearerAuth")
public class RepoController {

    private final RepoService repoService;
    private final RepoWebhookService repoWebhookService;

    public RepoController(RepoService repoService, RepoWebhookService repoWebhookService) {
        this.repoService = repoService;
        this.repoWebhookService = repoWebhookService;
    }

    @GetMapping
    @Operation(summary = "Kullanicinin repolarini listele")
    public List<RepoDto> getRepos(@AuthenticationPrincipal Long userId) {
        return repoService.getUserRepos(userId);
    }

    @GetMapping("/candidates")
    @Operation(summary = "Senkronlanmis ama henuz izlenmeyen (aday) repolar")
    public List<RepoDto> getCandidates(@AuthenticationPrincipal Long userId) {
        return repoService.getCandidates(userId);
    }

    @PostMapping("/{id}/track")
    @Operation(summary = "Aday repoyu izlemeye al")
    public RepoDto trackRepo(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return repoService.trackRepo(userId, id);
    }

    @PostMapping("/sync")
    @Operation(summary = "GitHub'dan repolari senkronize et")
    public ResponseEntity<Void> syncRepos(@AuthenticationPrincipal Long userId) {
        repoService.syncRepos(userId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Spesifik repo detayini getir")
    public RepoDto getRepo(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return repoService.getRepo(userId, id);
    }

    @PutMapping("/{id}/branch")
    @Operation(summary = "Taranacak dali ayarla (bos = varsayilan dal)")
    public RepoDto setBranch(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                             @RequestBody BranchRequest request) {
        return repoService.setTrackedBranch(userId, id, request.branch());
    }

    public record BranchRequest(String branch) {
    }

    @PutMapping("/{id}/auto-scan")
    @Operation(summary = "Otomatik taramayi ac/kapat (izlenen dala her push'ta tarama; GitHub webhook kurar, depoda admin yetkisi gerekir)")
    public RepoDto setAutoScan(@AuthenticationPrincipal Long userId, @PathVariable Long id,
                               @RequestBody AutoScanRequest request) {
        if (request.enabled()) {
            repoWebhookService.enable(userId, id);
        } else {
            repoWebhookService.disable(userId, id);
        }
        return repoService.getRepo(userId, id);
    }

    public record AutoScanRequest(boolean enabled) {
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Repoyu sil")
    public ResponseEntity<Void> deleteRepo(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        repoWebhookService.removeQuietly(userId, id);
        repoService.deleteRepo(userId, id);
        return ResponseEntity.noContent().build();
    }
}
