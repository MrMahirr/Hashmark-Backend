package dev.hashmark.common.retention;

import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.notification.repository.NotificationRepository;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.repository.ScanSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.function.IntSupplier;

/**
 * Saklama politikasını uygular (plan Bölüm 5.6): eski tarama işleri silinir, eski snapshot'lar
 * haftalığa indirgenir ve sonra silinir, okunmuş eski bildirimler silinir. Her adım idempotent; iki
 * instance aynı anda çalıştırsa da sonuç aynıdır (dağıtık kilit Faz 3'te).
 */
@Component
public class DataRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);
    /** Tek çalıştırmada bir tablo için en fazla parti sayısı; kalan bir sonraki güne kalır. */
    static final int MAX_BATCHES = 200;

    private final RetentionProperties props;
    private final ScanJobRepository scanJobs;
    private final ScanSnapshotRepository snapshots;
    private final NotificationRepository notifications;
    private final AppMetrics metrics;

    public DataRetentionJob(RetentionProperties props, ScanJobRepository scanJobs,
                            ScanSnapshotRepository snapshots, NotificationRepository notifications,
                            AppMetrics metrics) {
        this.props = props;
        this.scanJobs = scanJobs;
        this.snapshots = snapshots;
        this.notifications = notifications;
        this.metrics = metrics;
    }

    /** Varsayılan: her gün 04:30 (03:00 yedeğinden sonra). */
    @Scheduled(cron = "${retention.cron:0 30 4 * * *}")
    public void run() {
        if (!props.isEnabled()) {
            return;
        }
        // Saklama kullanıcılar arasıdır (RLS, V11)
        TenantContext.asSystem(this::apply);
    }

    private void apply() {
        try {
            int jobs = inBatches(() -> scanJobs.deleteFinishedOlderThan(props.getScanJobsDays(), props.getBatchSize()));
            int compacted = snapshots.compactToWeekly(props.getSnapshotsDetailDays());
            int expired = inBatches(() -> snapshots.deleteOlderThanMonths(props.getSnapshotsMonths(), props.getBatchSize()));
            int notes = inBatches(() -> notifications.deleteReadOlderThan(props.getNotificationsReadDays(), props.getBatchSize()));

            metrics.retentionDeleted("scan_jobs", jobs);
            metrics.retentionDeleted("scan_snapshots", compacted + expired);
            metrics.retentionDeleted("notifications", notes);
            if (jobs + compacted + expired + notes > 0) {
                log.info("Retention: {} scan job, {} snapshot (haftalığa indirgeme), {} snapshot (süresi dolan), "
                        + "{} bildirim silindi", jobs, compacted, expired, notes);
            }
        } catch (Exception e) {
            log.warn("Retention çalıştırması başarısız: {}", e.getMessage());
        }
    }

    private int inBatches(IntSupplier batch) {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = batch.getAsInt();
            total += n;
            if (n < props.getBatchSize()) {
                break;
            }
        }
        return total;
    }
}
