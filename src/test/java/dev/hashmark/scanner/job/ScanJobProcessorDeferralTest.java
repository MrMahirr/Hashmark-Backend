package dev.hashmark.scanner.job;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.common.resilience.GitHubResilience;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.notification.service.NotificationService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.service.BlameEnrichmentService;
import dev.hashmark.scanner.service.DebtParserService;
import dev.hashmark.scanner.service.GitHubFileService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanJobProcessorDeferralTest {

    private final UUID token = UUID.randomUUID();
    private ScanJobRepository jobs;
    private GitHubFileService files;
    private NotificationService notifications;
    private ScanProperties props;
    private SimpleMeterRegistry registry;
    private ScanJobProcessor processor;

    @BeforeEach
    void setUp() {
        jobs = mock(ScanJobRepository.class);
        files = mock(GitHubFileService.class);
        notifications = mock(NotificationService.class);
        UserRepository users = mock(UserRepository.class);
        RepoRepository repos = mock(RepoRepository.class);
        AesEncryptionUtil aes = mock(AesEncryptionUtil.class);
        props = new ScanProperties();
        registry = new SimpleMeterRegistry();

        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).githubToken("enc").build()));
        when(aes.decrypt("enc")).thenReturn("plain");
        when(repos.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(Repo.builder().id(10L).fullName("o/r").build()));

        processor = new ScanJobProcessor(jobs, mock(ScanResultWriter.class), files, mock(BlameEnrichmentService.class),
                mock(DebtParserService.class), users, repos, aes, notifications, new AppMetrics(registry),
                mock(ConsentService.class), props);
    }

    private ScanJob job(int deferrals) {
        return ScanJob.builder().id(5L).repoId(10L).userId(1L).status("RUNNING").deferrals(deferrals)
                .claimToken(token).build();
    }

    private void githubFails(ApiException e) {
        when(files.scanRepository(anyString(), anyString(), any(), any(), any())).thenThrow(e);
    }

    @Test
    void githubOutageDefersInsteadOfFailing() {
        githubFails(ApiException.serviceUnavailable(GitHubResilience.CODE_UNAVAILABLE, "down", 30));
        when(jobs.defer(eq(5L), eq(token), anyLong(), anyString())).thenReturn(true);

        processor.process(job(0));

        verify(jobs).defer(eq(5L), eq(token), anyLong(), anyString());
        verify(jobs, never()).complete(anyLong(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
        verify(notifications, never()).createNotification(anyLong(), anyString(), anyString(), anyString());
        assertEquals(1.0, registry.counter("hashmark.scan.deferred").count());
    }

    @Test
    void rateLimitDefersAtLeastUntilGithubSaysSo() {
        githubFails(ApiException.tooManyRequests("GITHUB_RATE_LIMITED", "limit", 600));
        when(jobs.defer(eq(5L), eq(token), anyLong(), anyString())).thenReturn(true);

        processor.process(job(0));

        verify(jobs).defer(eq(5L), eq(token),
                org.mockito.ArgumentMatchers.longThat(d -> d >= 600 && d <= 750), anyString());
    }

    @Test
    void failsAfterTooManyDeferrals() {
        githubFails(ApiException.serviceUnavailable(GitHubResilience.CODE_UNAVAILABLE, "down", 30));
        when(jobs.complete(eq(5L), eq(token), eq("FAILED"), anyInt(), anyInt(), anyInt(), anyInt(), isNull(), anyString()))
                .thenReturn(true);

        processor.process(job(props.getMaxDeferrals()));

        verify(jobs, never()).defer(anyLong(), any(), anyLong(), anyString());
        verify(notifications).createNotification(eq(1L), eq("Scan failed"), anyString(), eq("ERROR"));
    }

    @Test
    void permanentErrorFailsImmediately() {
        githubFails(ApiException.notFound("Repository not found on GitHub"));
        when(jobs.complete(eq(5L), eq(token), eq("FAILED"), anyInt(), anyInt(), anyInt(), anyInt(), isNull(), anyString()))
                .thenReturn(true);

        processor.process(job(0));

        verify(jobs, never()).defer(anyLong(), any(), anyLong(), anyString());
    }

    @Test
    void fencedOutRunDoesNotNotifyTheUser() {
        // İş bu arada devredildi (kapanış/kalp atışı): FAILED yazımı jeton uyuşmadığı için uygulanmaz
        githubFails(ApiException.notFound("x"));
        when(jobs.complete(anyLong(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any()))
                .thenReturn(false);

        processor.process(job(0));

        verify(notifications, never()).createNotification(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void deferDelayGrowsExponentiallyWithCap() {
        for (int i = 0; i < 50; i++) {
            long first = processor.deferDelaySeconds(job(0), null);
            long third = processor.deferDelaySeconds(job(2), null);
            long capped = processor.deferDelaySeconds(job(30), null);
            assertTrue(first >= 30 && first <= 38, "30 sn + %25'e kadar jitter: " + first);
            assertTrue(third >= 120 && third <= 150, "" + third);
            assertTrue(capped >= 900 && capped <= 1125, "üst sınır 15 dk + jitter: " + capped);
        }
    }
}
