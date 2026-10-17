package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.repository.ScanJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanQuotaServiceTest {

    private ScanJobRepository jobs;
    private RepoRepository repos;
    private ScanProperties props;
    private SimpleMeterRegistry registry;
    private ScanQuotaService quota;

    @BeforeEach
    void setUp() {
        jobs = mock(ScanJobRepository.class);
        repos = mock(RepoRepository.class);
        props = new ScanProperties();
        registry = new SimpleMeterRegistry();
        quota = new ScanQuotaService(jobs, repos, props, new AppMetrics(registry));
    }

    private Repo repo(Integer sizeKb, boolean tracked) {
        return Repo.builder().id(5L).userId(1L).sizeKb(sizeKb).tracked(tracked).build();
    }

    @Test
    void allowsScanWithinQuota() {
        when(jobs.countActiveByUser(1L)).thenReturn(2);
        when(jobs.countCreatedByUserWithinHours(1L, 24)).thenReturn(49);
        when(jobs.countPending()).thenReturn(10L);
        assertDoesNotThrow(() -> quota.checkCanScan(1L, repo(100, true)));
    }

    @Test
    void rejectsWhenTooManyActiveScans() {
        when(jobs.countActiveByUser(1L)).thenReturn(3);
        ApiException ex = assertThrows(ApiException.class, () -> quota.checkCanScan(1L, repo(null, true)));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getHttpStatus());
        assertEquals(ScanQuotaService.CODE_ACTIVE_LIMIT, ex.getCode());
        assertEquals(30L, ex.getRetryAfterSeconds());
        assertEquals(1.0, registry.counter("hashmark.scan.quota.rejected", "kind", "active").count());
    }

    @Test
    void rejectsWhenDailyLimitReached() {
        when(jobs.countCreatedByUserWithinHours(1L, 24)).thenReturn(50);
        ApiException ex = assertThrows(ApiException.class, () -> quota.checkCanScan(1L, repo(null, true)));
        assertEquals(ScanQuotaService.CODE_DAILY_LIMIT, ex.getCode());
    }

    @Test
    void rejectsWhenGlobalQueueIsFull() {
        when(jobs.countPending()).thenReturn(500L);
        ApiException ex = assertThrows(ApiException.class, () -> quota.checkCanScan(1L, repo(null, true)));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getHttpStatus());
        assertEquals(ScanQuotaService.CODE_QUEUE_FULL, ex.getCode());
    }

    @Test
    void rejectsTooLargeRepoBeforeCountingAnything() {
        ApiException ex = assertThrows(ApiException.class,
                () -> quota.checkCanScan(1L, repo(1024 * 1024 + 1, true)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getHttpStatus());
        assertEquals(ScanQuotaService.CODE_REPO_TOO_LARGE, ex.getCode());
        verify(jobs, never()).countActiveByUser(anyLong());
    }

    @Test
    void zeroDisablesQuota() {
        props.getQuota().setMaxActivePerUser(0);
        props.getQuota().setMaxPerDay(0);
        props.setMaxQueueDepth(0);
        props.getQuota().setMaxRepoSizeMb(0);
        assertDoesNotThrow(() -> quota.checkCanScan(1L, repo(Integer.MAX_VALUE, true)));
        verify(jobs, never()).countActiveByUser(anyLong());
    }

    @Test
    void trackingIsLimitedButAlreadyTrackedRepoIsFine() {
        when(repos.countTrackedByUserId(1L)).thenReturn(50);
        ApiException ex = assertThrows(ApiException.class, () -> quota.checkCanTrack(1L, repo(10, false)));
        assertEquals(ScanQuotaService.CODE_REPO_LIMIT, ex.getCode());
        assertDoesNotThrow(() -> quota.checkCanTrack(1L, repo(10, true)), "zaten izleniyor: sayıyı artırmaz");
    }
}
