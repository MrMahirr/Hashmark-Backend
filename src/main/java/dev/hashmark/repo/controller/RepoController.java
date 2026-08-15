package dev.hashmark.repo.controller;

import dev.hashmark.repo.dto.RepoDto;
import dev.hashmark.repo.service.RepoService;
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

    public RepoController(RepoService repoService) {
        this.repoService = repoService;
    }

    @GetMapping
    @Operation(summary = "Kullanicinin repolarini listele")
    public List<RepoDto> getRepos(@AuthenticationPrincipal Long userId) {
        return repoService.getUserRepos(userId);
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

    @DeleteMapping("/{id}")
    @Operation(summary = "Repoyu sil")
    public ResponseEntity<Void> deleteRepo(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        repoService.deleteRepo(userId, id);
        return ResponseEntity.noContent().build();
    }
}
