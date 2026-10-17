package dev.hashmark.common.retention;

import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.notification.repository.NotificationRepository;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.repository.ScanSnapshotRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataRetentionJobTest {

    private RetentionProperties props;
    private ScanJobRepository jobs;
    private ScanSnapshotRepository snapshots;
    private NotificationRepository notifications;
    private SimpleMeterRegistry registry;
    private DataRetentionJob job;

    @BeforeEach
    void setUp() {
        props = new RetentionProperties();
        props.setBatchSize(10);
        jobs = mock(ScanJobRepository.class);
        snapshots = mock(ScanSnapshotRepository.class);
        notifications = mock(NotificationRepository.class);
        registry = new SimpleMeterRegistry();
        job = new DataRetentionJob(props, jobs, snapshots, notifications, new AppMetrics(registry));
    }

    private double deleted(String table) {
        return registry.get("hashmark.retention.deleted").tag("table", table).counter().count();
    }

    @Test
    void deletesInBatchesUntilAPartialBatch() {
        when(jobs.deleteFinishedOlderThan(90, 10)).thenReturn(10, 10, 3);
        when(snapshots.compactToWeekly(90)).thenReturn(4);
        when(snapshots.deleteOlderThanMonths(24, 10)).thenReturn(0);
        when(notifications.deleteReadOlderThan(180, 10)).thenReturn(2);

        job.run();

        verify(jobs, times(3)).deleteFinishedOlderThan(90, 10);
        verify(snapshots, times(1)).deleteOlderThanMonths(24, 10);
        assertEquals(23, deleted("scan_jobs"));
        assertEquals(4, deleted("scan_snapshots"));
        assertEquals(2, deleted("notifications"));
    }

    @Test
    void stopsAfterMaxBatchesSoOneRunStaysBounded() {
        when(jobs.deleteFinishedOlderThan(anyInt(), anyInt())).thenReturn(10);

        job.run();

        verify(jobs, times(DataRetentionJob.MAX_BATCHES)).deleteFinishedOlderThan(90, 10);
    }

    @Test
    void doesNothingWhenDisabled() {
        props.setEnabled(false);
        job.run();
        verifyNoInteractions(jobs, snapshots, notifications);
    }

    @Test
    void neverThrowsEvenIfDatabaseFails() {
        when(jobs.deleteFinishedOlderThan(anyInt(), anyInt())).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(job::run);
        verify(snapshots, never()).compactToWeekly(anyInt());
    }
}
