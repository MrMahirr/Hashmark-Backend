package dev.hashmark.scanner.job;

import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.common.util.FingerprintUtil;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.dto.ReplaceResult;
import dev.hashmark.debt.model.Debt;
import dev.hashmark.notification.service.NotificationService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Kuyruktan alınmış tek bir tarama işini çalıştırır. Çağıran {@link ScanJobDispatcher}'dır. */
@Component
public class ScanJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(ScanJobProcessor.class);

    private final ScanJobRepository scanJobRepository;
    private final ScanResultWriter scanResultWriter;
    private final GitHubFileService gitHubFileService;
    private final BlameEnrichmentService blameEnrichmentService;
    private final DebtParserService debtParserService;
    private final UserRepository userRepository;
    private final RepoRepository repoRepository;
    private final AesEncryptionUtil aesEncryptionUtil;
    private final NotificationService notificationService;
    private final AppMetrics metrics;
    private final ConsentService consentService;
    private final ScanProperties scanProperties;

    public ScanJobProcessor(ScanJobRepository scanJobRepository, ScanResultWriter scanResultWriter,
                            GitHubFileService gitHubFileService, BlameEnrichmentService blameEnrichmentService,
                            DebtParserService debtParserService,
                            UserRepository userRepository, RepoRepository repoRepository,
                            AesEncryptionUtil aesEncryptionUtil, NotificationService notificationService,
                            AppMetrics metrics, ConsentService consentService, ScanProperties scanProperties) {
        this.scanProperties = scanProperties;
        this.metrics = metrics;
        this.consentService = consentService;
        this.scanJobRepository = scanJobRepository;
        this.scanResultWriter = scanResultWriter;
        this.gitHubFileService = gitHubFileService;
        this.blameEnrichmentService = blameEnrichmentService;
        this.debtParserService = debtParserService;
        this.userRepository = userRepository;
        this.repoRepository = repoRepository;
        this.aesEncryptionUtil = aesEncryptionUtil;
        this.notificationService = notificationService;
    }

    public void process(ScanJob job) {
        long startNanos = System.nanoTime();
        String result = "failed";
        try {
            result = doProcess(job) ? "done" : "failed";
        } finally {
            metrics.scanFinished(result, java.time.Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    /** @return iş başarıyla tamamlandıysa true */
    private boolean doProcess(ScanJob job) {
        Long jobId = job.getId();
        Long repoId = job.getRepoId();
        Long userId = job.getUserId();
        UUID token = job.getClaimToken();

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
            // Kuyruktaki iş onay geri çekildikten sonra da çalışmaz; özel depo ek onayı da işçide yeniden denetlenir
            consentService.requireForProcessing(userId);
            consentService.requirePrivateRepoAck(repo);

            List<Debt> allDebts = new ArrayList<>();

            ScanResult result = gitHubFileService.scanRepository(
                    repo.getFullName(),
                    plainToken,
                    job.getBranch(),
                    (path, content) -> allDebts.addAll(toDebts(repoId, path, content)),
                    processed -> scanJobRepository.updateProgress(jobId, token, processed));

            // İş kapatma + borçlar + repo damgaları + snapshot tek transaction'da yazılır
            Optional<ReplaceResult> replaced = scanResultWriter.write(jobId, token, repoId, allDebts, result,
                    buildMessage(result));
            if (replaced.isEmpty()) {
                log.warn("Scan job {} is no longer running; result discarded", jobId);
                return false;
            }

            notifyScanDone(userId, repo.getFullName(), replaced.get());
            enrichBlame(repoId, repo.getFullName(), plainToken, result.commitSha());
            return true;

        } catch (ApiException e) {
            if (e.isTransient() && deferrable(job)) {
                long delay = deferDelaySeconds(job, e.getRetryAfterSeconds());
                if (scanJobRepository.defer(jobId, token, delay, "Retrying in " + delay + "s: " + e.getMessage())) {
                    log.info("Scan job {} deferred for {}s ({})", jobId, delay, e.getMessage());
                    metrics.scanDeferred();
                }
                return false;
            }
            log.warn("Scan failed for repo {}: {}", repoId, e.getMessage());
            // Jetonla kapatılır: iş bu arada devredildiyse (kapanış, kalp atışı) kullanıcıya yanlış hata bildirilmez
            if (scanJobRepository.complete(jobId, token, "FAILED", 0, 0, 0, 0, null, e.getMessage())) {
                notifyQuietly(userId, "Scan failed", e.getMessage(), "ERROR");
            }
            return false;
        } catch (Exception e) {
            log.error("Scan failed for repo: " + repoId, e);
            if (scanJobRepository.complete(jobId, token, "FAILED", 0, 0, 0, 0, null, "Unexpected error while scanning")) {
                notifyQuietly(userId, "Scan failed", "Unexpected error while scanning", "ERROR");
            }
            return false;
        }
    }

    private boolean deferrable(ScanJob job) {
        int deferrals = job.getDeferrals() == null ? 0 : job.getDeferrals();
        return deferrals < scanProperties.getMaxDeferrals();
    }

    /**
     * Üstel geri çekilme (taban × 2^erteleme, üst sınırlı) + %0–25 jitter; GitHub'ın önerdiği bekleme
     * (Retry-After / rate-limit sıfırlanması) daha uzunsa o kullanılır.
     */
    long deferDelaySeconds(ScanJob job, Long retryAfterSeconds) {
        int deferrals = job.getDeferrals() == null ? 0 : job.getDeferrals();
        long backoff = Math.min(scanProperties.getDeferMaxSeconds(),
                scanProperties.getDeferBaseSeconds() << Math.min(deferrals, 20));
        long delay = Math.max(backoff, retryAfterSeconds == null ? 0 : retryAfterSeconds);
        return delay + ThreadLocalRandom.current().nextLong(delay / 4 + 1);
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
