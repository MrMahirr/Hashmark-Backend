package dev.hashmark.scanner.job;

import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class ScanJobDispatcherTest {

    private ScanJobRepository repository;
    private ScanJobProcessor processor;
    private ScanProperties properties;
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor manualExecutor = queued::add;

    @BeforeEach
    void setUp() {
        repository = mock(ScanJobRepository.class);
        processor = mock(ScanJobProcessor.class);
        properties = new ScanProperties();
        properties.setMaxConcurrent(2);
    }

    private ScanJob job(long id) {
        return ScanJob.builder().id(id).repoId(10L + id).userId(1L).status("RUNNING").build();
    }

    @Test
    void claimsFreeSlotsAndRunsJobs() {
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        when(repository.claimPending(2)).thenReturn(List.of(job(1), job(2)));

        dispatcher.tick();

        assertEquals(2, queued.size());
        assertEquals(2, dispatcher.inFlight());
        queued.forEach(Runnable::run);
        verify(processor).process(org.mockito.ArgumentMatchers.argThat(j -> j.getId() == 1L));
        verify(processor).process(org.mockito.ArgumentMatchers.argThat(j -> j.getId() == 2L));
        assertEquals(0, dispatcher.inFlight());
    }

    @Test
    void doesNotClaimWhenAllSlotsAreBusy() {
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        when(repository.claimPending(2)).thenReturn(List.of(job(1), job(2)));
        dispatcher.tick(); // iki iş yürütülmeyi bekliyor (inFlight = 2)

        dispatcher.tick();

        verify(repository).claimPending(2);
        verify(repository, never()).claimPending(0);
    }

    @Test
    void claimsOnlyRemainingCapacity() {
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        when(repository.claimPending(2)).thenReturn(List.of(job(1)));
        when(repository.claimPending(1)).thenReturn(List.of());

        dispatcher.tick(); // 1 iş sürüyor
        dispatcher.tick(); // boş yer: 1

        verify(repository).claimPending(1);
    }

    @Test
    void recoversStaleJobsOnEveryTick() {
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        when(repository.claimPending(anyInt())).thenReturn(List.of());

        dispatcher.tick();

        verify(repository).recoverStale(properties.getHeartbeatTimeoutMinutes(), properties.getMaxAttempts());
    }

    @Test
    void releasesJobWhenExecutorRejects() {
        Executor rejecting = r -> {
            throw new RejectedExecutionException("full");
        };
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, rejecting);
        when(repository.claimPending(2)).thenReturn(List.of(job(7)));

        dispatcher.tick();

        verify(repository).release(7L, null);
        assertEquals(0, dispatcher.inFlight());
    }

    @Test
    void stopReleasesUnfinishedJobsWithTheirTokenAndStopsClaiming() {
        properties.setShutdownGraceSeconds(0);
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        UUID token = UUID.randomUUID();
        ScanJob running = job(3);
        running.setClaimToken(token);
        when(repository.claimPending(2)).thenReturn(List.of(running));
        when(repository.release(3L, token)).thenReturn(true);
        dispatcher.start();
        dispatcher.tick(); // iş executor'a verildi ama bitmedi

        dispatcher.stop();

        verify(repository).release(3L, token);
        assertFalse(dispatcher.isRunning());
        dispatcher.tick();
        verify(repository).claimPending(2); // kapanıştan sonra yeni iş alınmaz
    }

    @Test
    void stopWaitsForRunningJobsWithinGrace() throws Exception {
        properties.setShutdownGraceSeconds(5);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, pool);
            when(repository.claimPending(2)).thenReturn(List.of(job(4)));
            org.mockito.Mockito.doAnswer(inv -> {
                Thread.sleep(300);
                return null;
            }).when(processor).process(any());
            dispatcher.tick();

            long start = System.nanoTime();
            dispatcher.stop();
            long ms = (System.nanoTime() - start) / 1_000_000;

            assertEquals(0, dispatcher.inFlight(), "iş bitene kadar beklenmeli");
            assertTrue(ms < 4_000, "iş bitince hemen dönmeli: " + ms);
            verify(repository, never()).release(org.mockito.ArgumentMatchers.eq(4L), any());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void jobClaimedDuringShutdownIsReleasedImmediately() {
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        UUID token = UUID.randomUUID();
        ScanJob late = job(9);
        late.setClaimToken(token);
        // stop() tick'in kontrolünden sonra ama sahiplenme sırasında çağrılmış gibi
        when(repository.claimPending(2)).thenAnswer(inv -> {
            properties.setShutdownGraceSeconds(0);
            dispatcher.stop();
            return List.of(late);
        });

        dispatcher.tick();

        verify(repository).release(9L, token);
        assertTrue(queued.isEmpty(), "çalıştırılmamalı");
    }

    @Test
    void survivesProcessorErrorsAndFreesSlot() {
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        when(repository.claimPending(2)).thenReturn(List.of(job(1)));
        doThrow(new IllegalStateException("boom")).when(processor).process(any());

        dispatcher.tick();
        queued.forEach(Runnable::run);

        assertEquals(0, dispatcher.inFlight());
    }

    @Test
    void tickNeverThrowsEvenIfRepositoryFails() {
        ScanJobDispatcher dispatcher = new ScanJobDispatcher(repository, processor, properties, manualExecutor);
        when(repository.recoverStale(anyLong(), anyInt())).thenThrow(new RuntimeException("db down"));

        dispatcher.tick();

        verify(repository, never()).claimPending(anyInt());
    }
}
