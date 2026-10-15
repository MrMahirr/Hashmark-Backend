package dev.hashmark.repo.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.repo.dto.GitHubRepoDto;
import dev.hashmark.repo.dto.RepoDto;
import dev.hashmark.scanner.service.GitHubFileService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RepoService {

    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final GitHubRepoService gitHubRepoService;
    private final AesEncryptionUtil aesEncryptionUtil;

    public RepoService(RepoRepository repoRepository, UserRepository userRepository, GitHubRepoService gitHubRepoService, AesEncryptionUtil aesEncryptionUtil) {
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.gitHubRepoService = gitHubRepoService;
        this.aesEncryptionUtil = aesEncryptionUtil;
    }

    public void syncRepos(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.unauthorized("User not found"));

        String decryptedToken = aesEncryptionUtil.decrypt(user.getGithubToken());
        
        List<GitHubRepoDto> githubRepos = gitHubRepoService.fetchUserRepos(decryptedToken);

        for (GitHubRepoDto dto : githubRepos) {
            Repo repoToSave = Repo.builder()
                    .userId(userId)
                    .githubRepoId(dto.getId())
                    .fullName(dto.getFullName())
                    .isPrivate(dto.getIsPrivate())
                    .build();
            repoRepository.save(repoToSave);
        }
    }

    public List<RepoDto> getUserRepos(Long userId) {
        return repoRepository.findAllByUserIdWithDebtCount(userId).stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    public RepoDto getRepo(Long userId, Long repoId) {
        Repo repo = repoRepository.findByIdAndUserIdWithDebtCount(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found"));
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
        repoRepository.deleteByIdAndUserId(repoId, userId);
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
                .build();
    }
}
