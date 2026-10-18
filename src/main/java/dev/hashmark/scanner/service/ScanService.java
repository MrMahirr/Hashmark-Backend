package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.dto.ScanStartResponse;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class ScanService {

    private final RepoRepository repoRepository;
    private final ScanJobRepository scanJobRepository;
    private final ConsentService consentService;
    private final ScanQuotaService quotaService;

    public ScanService(RepoRepository repoRepository, ScanJobRepository scanJobRepository, ConsentService consentService,
                       ScanQuotaService quotaService) {
        this.repoRepository = repoRepository;
        this.scanJobRepository = scanJobRepository;
        this.consentService = consentService;
        this.quotaService = quotaService;
    }

    /** İşi kalıcı kuyruğa ekler; çalıştırmayı {@code ScanJobDispatcher} üstlenir. */
    public ScanStartResponse startScan(Long repoId, Long userId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found or not owned by user"));
        if (!Boolean.TRUE.equals(repo.getTracked())) {
            throw ApiException.badRequest("Repository is not tracked; add it first");
        }
        consentService.requirePrivateRepoAck(repo);
        quotaService.checkCanScan(userId, repo);

        ScanJob job;
        try {
            // Benzersiz kısmi indeks (PENDING/RUNNING) eşzamanlı taramayı atomik olarak engeller
            job = scanJobRepository.enqueue(repoId, userId, repo.getTrackedBranch());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A scan is already queued or running for this repository");
        }

        return ScanStartResponse.builder()
                .repoId(repo.getId())
                .jobId(job.getId())
                .status(job.getStatus())
                .build();
    }

    /**
     * @param outcome QUEUED (yeni iş), COALESCED (çalışan iş bitince yeniden taranacak), ALREADY_QUEUED (bekleyen iş
     *                en son commit'i zaten tarayacak), SKIPPED (onay eksik vb.; {@code reason} açıklar)
     */
    public record AutoScanResult(String outcome, Long jobId, String reason) {
        static AutoScanResult skipped(String reason) {
            return new AutoScanResult("SKIPPED", null, reason);
        }
    }

    /**
     * Push webhook'u veya bekleyen yeniden tarama isteği için iş kuyruğa alır. Depo sahibinin kapsamında
     * çağrılmalıdır (RLS). Hata fırlatmaz: kuyruğa alınamayan istek {@code SKIPPED} döner.
     *
     * <p>Aynı depoda tek aktif iş olabilir. Tarama ÇALIŞIRKEN gelen push kaybolmasın diye depoya yeniden tarama
     * isteği yazılır; {@code ScanJobProcessor} iş bitince bunu tüketip yeni iş açar.
     */
    public AutoScanResult enqueueAutomatic(Repo repo) {
        if (consentService.isProcessingBlocked(repo.getUserId())) {
            return AutoScanResult.skipped("consent required");
        }
        try {
            consentService.requirePrivateRepoAck(repo);
        } catch (ApiException e) {
            return AutoScanResult.skipped("private repository notice not confirmed");
        }
        // Otomatik taramalar da elle başlatılanlarla aynı kotaya tabidir: sık push yapan depo GitHub kotasını,
        // işçileri ya da ortak kuyruğu tüketemez
        try {
            quotaService.checkCanScan(repo.getUserId(), repo);
        } catch (ApiException e) {
            return AutoScanResult.skipped(e.getCode() != null ? e.getCode() : e.getMessage());
        }

        try {
            ScanJob job = scanJobRepository.enqueue(repo.getId(), repo.getUserId(), repo.getTrackedBranch(),
                    ScanJobRepository.TRIGGER_PUSH);
            return new AutoScanResult("QUEUED", job.getId(), null);
        } catch (DuplicateKeyException e) {
            if (!repoRepository.requestRescanIfRunning(repo.getId())) {
                return new AutoScanResult("ALREADY_QUEUED", null, null);
            }
        }
        // Çalışan iş, istek yazılmadan hemen önce bitmiş olabilir: bir kez daha dene
        try {
            ScanJob job = scanJobRepository.enqueue(repo.getId(), repo.getUserId(), repo.getTrackedBranch(),
                    ScanJobRepository.TRIGGER_PUSH);
            repoRepository.consumeRescanRequest(repo.getId());
            return new AutoScanResult("QUEUED", job.getId(), null);
        } catch (DuplicateKeyException e) {
            return new AutoScanResult("COALESCED", null, null);
        }
    }

    /**
     * PR borç kontrolünü kuyruğa alır (depo sahibinin kapsamında). Tarama kotalarına tabi değildir: yalnızca
     * değişen dosyalar okunur; aynı PR'a art arda gelen push'lar bekleyen tek kontrolde birleşir.
     */
    public AutoScanResult enqueuePrCheck(Repo repo, int prNumber, String headSha, String baseSha) {
        if (consentService.isProcessingBlocked(repo.getUserId())) {
            return AutoScanResult.skipped("consent required");
        }
        try {
            consentService.requirePrivateRepoAck(repo);
        } catch (ApiException e) {
            return AutoScanResult.skipped("private repository notice not confirmed");
        }
        ScanJob job = scanJobRepository.enqueuePrCheck(repo.getId(), repo.getUserId(), prNumber, headSha, baseSha);
        return new AutoScanResult("QUEUED", job.getId(), null);
    }

    public ScanJob getScanStatus(Long repoId, Long userId) {
        repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found or not owned by user"));

        return scanJobRepository.findLatestByRepoId(repoId)
                .orElseThrow(() -> ApiException.notFound("No scan job found for this repo"));
    }
}
