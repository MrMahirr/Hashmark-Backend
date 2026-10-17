package dev.hashmark.repo.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.repo.dto.GitHubRepoDto;
import dev.hashmark.repo.dto.RepoDto;
import dev.hashmark.scanner.service.GitHubFileService;
import dev.hashmark.scanner.service.ScanQuotaService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RepoService {

    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final GitHubRepoService gitHubRepoService;
    private final AesEncryptionUtil aesEncryptionUtil;
    private final ScanQuotaService quotaService;

    public RepoService(RepoRepository repoRepository, UserRepository userRepository, GitHubRepoService gitHubRepoService,
                       AesEncryptionUtil aesEncryptionUtil, ScanQuotaService quotaService) {
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.gitHubRepoService = gitHubRepoService;
        this.aesEncryptionUtil = aesEncryptionUtil;
        this.quotaService = quotaService;
    }

    public void syncRepos(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User not found"));

        if (user.getGithubToken() == null || user.getGithubToken().isBlank()) {
            throw ApiException.badRequest("GitHub account is not connected");
        }
        String decryptedToken = aesEncryptionUtil.decrypt(user.getGithubToken());
        
        List<GitHubRepoDto> githubRepos = gitHubRepoService.fetchUserRepos(decryptedToken);

        for (GitHubRepoDto dto : githubRepos) {
            Repo repoToSave = Repo.builder()
                    .userId(userId)
                    .githubRepoId(dto.getId())
                    .fullName(dto.getFullName())
                    .isPrivate(dto.getIsPrivate())
                    .sizeKb(dto.getSize())
                    .build();
            repoRepository.save(repoToSave);
        }
    }

    /** İzlenen depolar (borç sayısı ve etiket kırılımıyla). */
    public List<RepoDto> getUserRepos(Long userId) {
        Map<Long, Map<String, Integer>> breakdown = repoRepository.findOpenBreakdownByUserId(userId);
        return repoRepository.findAllByUserIdWithDebtCount(userId).stream()
                .peek(repo -> repo.setDebtBreakdown(breakdown.getOrDefault(repo.getId(), Map.of())))
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    /** GitHub'dan senkronlanmış ama henüz izlenmeyen depolar. */
    public List<RepoDto> getCandidates(Long userId) {
        return repoRepository.findCandidatesByUserId(userId).stream()
                .map(repo -> {
                    repo.setDebtCount(0);
                    return mapToDto(repo);
                })
                .collect(Collectors.toList());
    }

    /** Adayı izlemeye alır; sonraki taramalara dahil olur. */
    public RepoDto trackRepo(Long userId, Long repoId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found"));
        quotaService.checkCanTrack(userId, repo);
        if (repoRepository.markTracked(repoId, userId) == 0) {
            throw ApiException.notFound("Repo not found");
        }
        return getRepo(userId, repoId);
    }

    public RepoDto getRepo(Long userId, Long repoId) {
        Repo repo = repoRepository.findByIdAndUserIdWithDebtCount(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found"));
        repo.setDebtBreakdown(repoRepository.findOpenBreakdownByUserId(userId).getOrDefault(repoId, Map.of()));
        return mapToDto(repo);
    }

    /**
     * Taramanın izleyeceği dalı ayarlar. Boş/null → deponun varsayılan dalı.
     * Dal GitHub'da bulunmuyorsa bir sonraki tarama açıklayıcı bir hatayla başarısız olur.
     */
    public RepoDto setTrackedBranch(Long userId, Long repoId, String branch) {
        String normalized = (branch == null || branch.isBlank()) ? null : branch.trim();
        if (normalized != null && !GitHubFileService.isValidBranch(normalized)) {
            throw ApiException.badRequest("Invalid branch name");
        }
        if (repoRepository.updateTrackedBranch(repoId, userId, normalized) == 0) {
            throw ApiException.notFound("Repo not found");
        }
        return getRepo(userId, repoId);
    }

    public void deleteRepo(Long userId, Long repoId) {
        // Başkasının deposu "yok" sayılır (K4): sessiz başarı yerine 404
        if (repoRepository.deleteByIdAndUserId(repoId, userId) == 0) {
            throw ApiException.notFound("Repository not found");
        }
    }

    private RepoDto mapToDto(Repo repo) {
        return RepoDto.builder()
                .id(repo.getId())
                .userId(repo.getUserId())
                .githubRepoId(repo.getGithubRepoId())
                .fullName(repo.getFullName())
                .isPrivate(repo.getIsPrivate())
                .lastScannedAt(repo.getLastScannedAt())
                .createdAt(repo.getCreatedAt())
                .debtCount(repo.getDebtCount())
                .trackedBranch(repo.getTrackedBranch())
                .lastScannedSha(repo.getLastScannedSha())
                .tracked(repo.getTracked())
                .autoScan(repo.getWebhookId() != null)
                .debtBreakdown(repo.getDebtBreakdown())
                .build();
    }
}
