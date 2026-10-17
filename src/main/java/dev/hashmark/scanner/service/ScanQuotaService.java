package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Kullanıcı başına kotalar ve kuyruk üst sınırı (plan Faz 3). Pahalı iş (tarama) kuyruğa girmeden reddedilir;
 * tek kullanıcı GitHub kotasını, işçileri ya da kuyruğu tüketemez. Değer 0 ise o kota kapalıdır.
 *
 * <p>Sayım ile ekleme arasında yarış vardır (aynı anda iki istek sınırı bir aşabilir); kotalar koruma amaçlıdır,
 * faturalama değil. Depo başına tek aktif iş ise veritabanında benzersiz indeksle kesin olarak korunur.
 */
@Service
public class ScanQuotaService {

    public static final String CODE_ACTIVE_LIMIT = "SCAN_ACTIVE_LIMIT";
    public static final String CODE_DAILY_LIMIT = "SCAN_DAILY_LIMIT";
    public static final String CODE_QUEUE_FULL = "SCAN_QUEUE_FULL";
    public static final String CODE_REPO_LIMIT = "REPO_LIMIT_REACHED";
    public static final String CODE_REPO_TOO_LARGE = "REPO_TOO_LARGE";

    private final ScanJobRepository scanJobRepository;
    private final RepoRepository repoRepository;
    private final ScanProperties properties;
    private final AppMetrics metrics;

    public ScanQuotaService(ScanJobRepository scanJobRepository, RepoRepository repoRepository,
                            ScanProperties properties, AppMetrics metrics) {
        this.scanJobRepository = scanJobRepository;
        this.repoRepository = repoRepository;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** Yeni tarama kuyruğa eklenmeden önce. */
    public void checkCanScan(Long userId, Repo repo) {
        checkRepoSize(repo);
        ScanProperties.Quota quota = properties.getQuota();

        if (quota.getMaxActivePerUser() > 0
                && scanJobRepository.countActiveByUser(userId) >= quota.getMaxActivePerUser()) {
            metrics.scanQuotaRejected("active");
            throw ApiException.tooManyRequests(CODE_ACTIVE_LIMIT, "You already have " + quota.getMaxActivePerUser()
                    + " scans queued or running. Please wait for one to finish", 30);
        }
        if (quota.getMaxPerDay() > 0
                && scanJobRepository.countCreatedByUserWithinHours(userId, 24) >= quota.getMaxPerDay()) {
            metrics.scanQuotaRejected("daily");
            throw ApiException.tooManyRequests(CODE_DAILY_LIMIT, "Daily scan limit (" + quota.getMaxPerDay()
                    + ") reached. Please try again later", 3600);
        }
        // Kuyruk tüm kullanıcılar için ortaktır: sayım sistem kapsamında (RLS kullanıcıya yalnız kendi satırını gösterir)
        if (properties.getMaxQueueDepth() > 0
                && TenantContext.asSystem(scanJobRepository::countPending) >= properties.getMaxQueueDepth()) {
            metrics.scanQuotaRejected("queue_full");
            throw ApiException.serviceUnavailable(CODE_QUEUE_FULL,
                    "The scan queue is full right now. Please try again in a few minutes", 60);
        }
    }

    /** Aday depo izlemeye alınmadan önce. */
    public void checkCanTrack(Long userId, Repo repo) {
        checkRepoSize(repo);
        int max = properties.getQuota().getMaxTrackedRepos();
        if (max > 0 && !Boolean.TRUE.equals(repo.getTracked()) && repoRepository.countTrackedByUserId(userId) >= max) {
            metrics.scanQuotaRejected("repos");
            throw new ApiException(HttpStatus.FORBIDDEN, CODE_REPO_LIMIT,
                    "You can track at most " + max + " repositories. Remove one to add another");
        }
    }

    private void checkRepoSize(Repo repo) {
        long maxMb = properties.getQuota().getMaxRepoSizeMb();
        if (maxMb > 0 && repo.getSizeKb() != null && repo.getSizeKb() > maxMb * 1024) {
            metrics.scanQuotaRejected("repo_size");
            throw new ApiException(HttpStatus.FORBIDDEN, CODE_REPO_TOO_LARGE,
                    "Repository is too large to scan (limit " + maxMb + " MB)");
        }
    }
}
