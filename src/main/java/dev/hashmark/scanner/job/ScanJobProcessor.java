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
import dev.hashmark.scanner.service.GitHubFileService.ScanResult;
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

            List<Debt> allDebts = new ArrayList<>();

            ScanResult result = gitHubFileService.scanRepository(
                    repo.getFullName(),
                    plainToken,
                    (path, content) -> {
                        for (DebtDto dto : debtParserService.parse(content, path)) {
                            allDebts.add(Debt.builder()
                                    .repoId(repoId)
                                    .filePath(dto.getFilePath())
                                    .lineNo(dto.getLineNo())
                                    .label(dto.getLabel())
                                    .content(dto.getContent())
                                    .build());
                        }
                    },
                    processed -> scanJobRepository.updateProgress(jobId, processed));

            // Kısmi taramada yalnızca gerçekten taranan dosyaların borçları çözülür;
            // okunamayan veya çok büyük dosyaların borçları ise açık bırakılır.
            debtRepository.replaceForRepo(
                    repoId,
                    allDebts,
                    result.truncated() ? result.scannedPaths() : null,
                    result.keepOpenPaths());

            scanJobRepository.complete(jobId, "DONE", allDebts.size(), result.scanned(), result.skipped(),
                    result.failed(), buildMessage(result));
            repoRepository.updateLastScannedAt(repoId, LocalDateTime.now());

        } catch (ApiException e) {
            log.warn("Scan failed for repo {}: {}", repoId, e.getMessage());
            scanJobRepository.complete(jobId, "FAILED", 0, 0, 0, 0, e.getMessage());
        } catch (Exception e) {
            log.error("Scan failed for repo: " + repoId, e);
            scanJobRepository.complete(jobId, "FAILED", 0, 0, 0, 0, "Unexpected error while scanning");
        }
    }

    private String buildMessage(ScanResult result) {
        List<String> parts = new ArrayList<>();
        if (result.truncated()) {
            parts.add("Partial scan: " + result.truncationReason());
        }
        if (result.failed() > 0) {
            parts.add(result.failed() + " file(s) could not be processed");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }
}
