package dev.hashmark.scanner.job;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.FingerprintUtil;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.ReplaceResult;
import dev.hashmark.debt.model.Debt;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.notification.service.NotificationService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.repository.ScanSnapshotRepository;
import dev.hashmark.scanner.service.BlameEnrichmentService;
import dev.hashmark.scanner.service.DebtParserService;
import dev.hashmark.scanner.service.GitHubFileService;
import dev.hashmark.scanner.service.GitHubFileService.ScanResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Kuyruktan alınmış tek bir tarama işini çalıştırır. Çağıran {@link ScanJobDispatcher}'dır. */
@Component
public class ScanJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ScanJobProcessor.class);

    private final ScanJobRepository scanJobRepository;
    private final ScanSnapshotRepository scanSnapshotRepository;
    private final GitHubFileService gitHubFileService;
    private final BlameEnrichmentService blameEnrichmentService;
    private final DebtParserService debtParserService;
    private final DebtRepository debtRepository;
    private final UserRepository userRepository;
    private final RepoRepository repoRepository;
    private final AesEncryptionUtil aesEncryptionUtil;
    private final NotificationService notificationService;

    public ScanJobProcessor(ScanJobRepository scanJobRepository, ScanSnapshotRepository scanSnapshotRepository,
                            GitHubFileService gitHubFileService, BlameEnrichmentService blameEnrichmentService,
                            DebtParserService debtParserService, DebtRepository debtRepository,
                            UserRepository userRepository, RepoRepository repoRepository,
                            AesEncryptionUtil aesEncryptionUtil, NotificationService notificationService) {
        this.scanJobRepository = scanJobRepository;
        this.scanSnapshotRepository = scanSnapshotRepository;
        this.gitHubFileService = gitHubFileService;
        this.blameEnrichmentService = blameEnrichmentService;
        this.debtParserService = debtParserService;
        this.debtRepository = debtRepository;
        this.userRepository = userRepository;
        this.repoRepository = repoRepository;
        this.aesEncryptionUtil = aesEncryptionUtil;
        this.notificationService = notificationService;
    }

    public void process(ScanJob job) {
        Long jobId = job.getId();
        Long repoId = job.getRepoId();
        Long userId = job.getUserId();

        try {
            if (userId == null) {
                throw ApiException.badRequest("Scan job has no owner");
            }
            User user = userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
            if (user.getGithubToken() == null || user.getGithubToken().isBlank()) {
                throw ApiException.badRequest("GitHub account is not connected");
            }
            String plainToken = aesEncryptionUtil.decrypt(user.getGithubToken());
            Repo repo = repoRepository.findByIdAndUserId(repoId, userId).orElseThrow(() -> ApiException.notFound("Repo not found"));

            List<Debt> allDebts = new ArrayList<>();

            ScanResult result = gitHubFileService.scanRepository(
                    repo.getFullName(),
                    plainToken,
                    job.getBranch(),
                    (path, content) -> allDebts.addAll(toDebts(repoId, path, content)),
                    processed -> scanJobRepository.updateProgress(jobId, processed));

            // Kısmi taramada yalnızca gerçekten taranan dosyaların borçları çözülür;
            // okunamayan veya çok büyük dosyaların borçları ise açık bırakılır.
            ReplaceResult replaced = debtRepository.replaceForRepo(
                    repoId,
                    allDebts,
                    result.commitSha(),
                    result.truncated() ? result.scannedPaths() : null,
                    result.keepOpenPaths());

            scanJobRepository.complete(jobId, "DONE", allDebts.size(), result.scanned(), result.skipped(),
                    result.failed(), result.commitSha(), buildMessage(result));
            repoRepository.updateLastScannedAt(repoId, LocalDateTime.now());
            if (result.commitSha() != null) {
                repoRepository.updateLastScannedSha(repoId, result.commitSha());
            }

            saveSnapshot(repoId, jobId, result.commitSha(), replaced);
            notifyScanDone(userId, repo.getFullName(), replaced);
            enrichBlame(repoId, repo.getFullName(), plainToken, result.commitSha());

        } catch (ApiException e) {
            log.warn("Scan failed for repo {}: {}", repoId, e.getMessage());
            scanJobRepository.complete(jobId, "FAILED", 0, 0, 0, 0, null, e.getMessage());
            notifyQuietly(userId, "Scan failed", e.getMessage(), "ERROR");
        } catch (Exception e) {
            log.error("Scan failed for repo: " + repoId, e);
            scanJobRepository.complete(jobId, "FAILED", 0, 0, 0, 0, null, "Unexpected error while scanning");
            notifyQuietly(userId, "Scan failed", "Unexpected error while scanning", "ERROR");
        }
    }

    private void notifyScanDone(Long userId, String repoName, ReplaceResult replaced) {
        String description = repoName + ": " + replaced.openTotal() + " open, +" + replaced.added()
                + " new, " + replaced.resolved() + " resolved";
        notifyQuietly(userId, "Scan completed", description, "SUCCESS");
        if (replaced.reopened() > 0) {
            notifyQuietly(userId, "Debt reopened",
                    repoName + ": " + replaced.reopened() + " item(s) marked resolved are still in the code", "WARNING");
        }
    }

    /** Bildirim yazılamaması taramayı bozmamalı. */
    private void notifyQuietly(Long userId, String title, String description, String type) {
        if (userId == null) {
            return;
        }
        try {
            notificationService.createNotification(userId, title, description, type);
        } catch (Exception e) {
            log.warn("Could not create notification for user {}: {}", userId, e.getMessage());
        }
    }

    /** Dosyadaki yorumları ayrıştırır; aynı (etiket+içerik) tekrarlarına sıra numarası verir. */
    List<Debt> toDebts(Long repoId, String path, String content) {
        List<Debt> debts = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (DebtDto dto : debtParserService.parse(content, path)) {
            String fingerprint = FingerprintUtil.fingerprint(dto.getLabel(), dto.getContent());
            int occurrence = seen.merge(fingerprint, 1, Integer::sum) - 1;
            debts.add(Debt.builder()
                    .repoId(repoId)
                    .filePath(dto.getFilePath())
                    .lineNo(dto.getLineNo())
                    .label(dto.getLabel())
                    .content(dto.getContent())
                    .fingerprint(fingerprint)
                    .occurrence(occurrence)
                    .parsedAssignee(dto.getAssignee())
                    .priority(dto.getPriority())
                    .dueDate(dto.getDueDate())
                    .issueRef(dto.getIssueRef())
                    .build());
        }
        return debts;
    }

    private void saveSnapshot(Long repoId, Long jobId, String sha, ReplaceResult replaced) {
        try {
            scanSnapshotRepository.save(repoId, jobId, sha, replaced.openTotal(), replaced.openByLabel(),
                    replaced.added(), replaced.resolved());
        } catch (Exception e) {
            log.warn("Could not save scan snapshot for repo {}", repoId, e);
        }
    }

    private void enrichBlame(Long repoId, String fullName, String token, String sha) {
        try {
            int updated = blameEnrichmentService.enrich(repoId, fullName, token, sha);
            if (updated > 0) {
                log.info("Blame enrichment updated {} debt(s) of repo {}", updated, repoId);
            }
        } catch (Exception e) {
            log.warn("Blame enrichment failed for repo {}", repoId, e);
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
