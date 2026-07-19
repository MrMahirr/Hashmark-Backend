package dev.hashmark.scanner.job;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.model.Debt;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.service.DebtParserService;
import dev.hashmark.scanner.service.GitHubFileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class ScanJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ScanJobProcessor.class);

    private final ScanJobRepository scanJobRepository;
    private final GitHubFileService gitHubFileService;
    private final DebtParserService debtParserService;
    private final DebtRepository debtRepository;
    private final UserRepository userRepository;
    private final RepoRepository repoRepository;
    private final AesEncryptionUtil aesEncryptionUtil;

    public ScanJobProcessor(ScanJobRepository scanJobRepository, GitHubFileService gitHubFileService, DebtParserService debtParserService, DebtRepository debtRepository, UserRepository userRepository, RepoRepository repoRepository, AesEncryptionUtil aesEncryptionUtil) {
        this.scanJobRepository = scanJobRepository;
        this.gitHubFileService = gitHubFileService;
        this.debtParserService = debtParserService;
        this.debtRepository = debtRepository;
        this.userRepository = userRepository;
        this.repoRepository = repoRepository;
        this.aesEncryptionUtil = aesEncryptionUtil;
    }

    @Async("taskExecutor")
    public void processScan(Long repoId, Long userId, Long jobId) {
        try {
            User user = userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
            String plainToken = aesEncryptionUtil.decrypt(user.getGithubToken());
            Repo repo = repoRepository.findByIdAndUserId(repoId, userId).orElseThrow(() -> ApiException.notFound("Repo not found"));

            List<String> filePaths = gitHubFileService.listRepoFiles(repo.getFullName(), plainToken);

            List<Debt> allDebts = new ArrayList<>();

            for (String filePath : filePaths) {
                try {
                    String content = gitHubFileService.getFileContent(repo.getFullName(), filePath, plainToken);
                    List<DebtDto> parsedDebts = debtParserService.parse(content, filePath);

                    for (DebtDto dto : parsedDebts) {
                        allDebts.add(Debt.builder()
                                .repoId(repoId)
                                .filePath(dto.getFilePath())
                                .lineNo(dto.getLineNo())
                                .label(dto.getLabel())
                                .content(dto.getContent())
                                .build());
                    }
                } catch (Exception e) {
                    log.warn("Failed to process file: " + filePath, e);
                }
            }

            debtRepository.markAllAsResolvedForRepo(repoId);
            debtRepository.saveAll(repoId, allDebts);

            scanJobRepository.updateStatus(jobId, "DONE", allDebts.size());
            repoRepository.updateLastScannedAt(repoId, LocalDateTime.now());

        } catch (Exception e) {
            log.error("Scan failed for repo: " + repoId, e);
            scanJobRepository.updateStatus(jobId, "FAILED", 0);
        }
    }
}
