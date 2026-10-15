package dev.hashmark.scanner.job;

import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        verify(repository).release(7L);
        assertEquals(0, dispatcher.inFlight());
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
