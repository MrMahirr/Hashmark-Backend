package dev.hashmark.scanner.job;

import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Veritabanındaki PENDING işleri alıp çalıştırır. Kuyruk veritabanında olduğu için uygulama
 * yeniden başlasa da işler kaybolmaz; kalp atışı kesilen işler yeniden kuyruğa alınır.
 */
@Component
public class ScanJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ScanJobDispatcher.class);

    private final ScanJobRepository scanJobRepository;
    private final ScanJobProcessor processor;
    private final ScanProperties properties;
    private final Executor executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public ScanJobDispatcher(ScanJobRepository scanJobRepository, ScanJobProcessor processor,
                             ScanProperties properties, @Qualifier("taskExecutor") Executor executor) {
        this.scanJobRepository = scanJobRepository;
        this.processor = processor;
        this.properties = properties;
        this.executor = executor;
    }

    @Scheduled(fixedDelayString = "${scan.dispatch-interval-ms:2000}",
               initialDelayString = "${scan.dispatch-interval-ms:2000}")
    public void tick() {
        // Kuyruk kullanıcılar arasıdır: kurtarma/sahiplenme/geri bırakma sistem kapsamında (RLS, V11)
        TenantContext.asSystem(this::dispatch);
    }

    private void dispatch() {
        try {
            int recovered = scanJobRepository.recoverStale(
                    properties.getHeartbeatTimeoutMinutes(), properties.getMaxAttempts());
            if (recovered > 0) {
                log.warn("Recovered {} interrupted scan job(s)", recovered);
            }

            int free = properties.getMaxConcurrent() - inFlight.get();
            if (free <= 0) {
                return;
            }
            List<ScanJob> jobs = scanJobRepository.claimPending(free);
            if (jobs == null) {
                return;
            }
            for (ScanJob job : jobs) {
                run(job);
            }
        } catch (Exception e) {
            log.error("Scan dispatcher tick failed", e);
        }
    }

    private void run(ScanJob job) {
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    // İş, sahibinin kapsamında işlenir: başka kullanıcının satırına yazamaz (WITH CHECK).
                    // Sahibi olmayan eski iş yalnızca FAILED işaretlenebilsin diye sistem kapsamında kalır.
                    if (job.getUserId() != null) {
                        TenantContext.asUser(job.getUserId(), () -> processor.process(job));
                    } else {
                        TenantContext.asSystem(() -> processor.process(job));
                    }
                } catch (Exception e) {
                    log.error("Unhandled error while processing scan job {}", job.getId(), e);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.decrementAndGet();
            scanJobRepository.release(job.getId());
            log.warn("Executor rejected scan job {}, returned to queue", job.getId());
        }
    }

    int inFlight() {
        return inFlight.get();
    }
}
