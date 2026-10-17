package dev.hashmark.scanner.job;

import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Veritabanındaki PENDING işleri alıp çalıştırır. Kuyruk veritabanında olduğu için uygulama
 * yeniden başlasa da işler kaybolmaz; kalp atışı kesilen işler yeniden kuyruğa alınır.
 *
 * <p><b>Düzgün kapanış</b> ({@link SmartLifecycle#stop()}): yeni iş alınmaz, çalışanların bitmesi
 * {@code scan.shutdown-grace-seconds} kadar beklenir, bitmeyenler jetonlarıyla kuyruğa geri bırakılır. Böylece
 * başka bir instance (ya da yeniden açılış) işi 5 dakikalık kalp atışı zaman aşımını beklemeden devralır; geride
 * kalan eski çalıştırmanın geç gelen sonucu jeton uyuşmadığı için yazılmaz.
 */
@Component
public class ScanJobDispatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ScanJobDispatcher.class);

    private final ScanJobRepository scanJobRepository;
    private final ScanJobProcessor processor;
    private final ScanProperties properties;
    private final Executor executor;
    private final AtomicInteger inFlight = new AtomicInteger();
    /** Bu instance'ta çalışan işler: iş kimliği → sahiplenme jetonu + işçi iş parçacığı. */
    private final Map<Long, Running> runningJobs = new ConcurrentHashMap<>();

    private static final class Running {
        final UUID token;
        volatile Thread worker;

        Running(UUID token) {
            this.token = token;
        }
    }
    private volatile boolean accepting = true;
    private volatile boolean lifecycleRunning;

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
        if (!accepting) {
            return;
        }
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
                if (accepting) {
                    run(job);
                } else {
                    // Kapanış sahiplenme sırasında başladı: hemen geri bırak
                    scanJobRepository.release(job.getId(), job.getClaimToken());
                }
            }
        } catch (Exception e) {
            log.error("Scan dispatcher tick failed", e);
        }
    }

    private void run(ScanJob job) {
        inFlight.incrementAndGet();
        Running running = new Running(job.getClaimToken());
        runningJobs.put(job.getId(), running);
        try {
            executor.execute(() -> {
                running.worker = Thread.currentThread();
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
                    running.worker = null;
                    runningJobs.remove(job.getId());
                    inFlight.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException e) {
            runningJobs.remove(job.getId());
            inFlight.decrementAndGet();
            scanJobRepository.release(job.getId(), job.getClaimToken());
            log.warn("Executor rejected scan job {}, returned to queue", job.getId());
        }
    }

    // ------------------------------------------------------------------ yaşam döngüsü

    @Override
    public void start() {
        accepting = true;
        lifecycleRunning = true;
    }

    /**
     * Web sunucusunun düzgün kapanışından ve executor'un kapatılmasından <b>önce</b> çalışır (en yüksek faz).
     * Senkron: en fazla {@code shutdown-grace-seconds} + geri bırakma süresi kadar bloklar.
     */
    @Override
    public void stop() {
        accepting = false;
        long deadline = System.nanoTime() + properties.getShutdownGraceSeconds() * 1_000_000_000L;
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        releaseUnfinished();
        lifecycleRunning = false;
    }

    /**
     * Hâlâ çalışan işleri kuyruğa geri bırakır ve işçilerini keser (GitHub çağrısı/bekleme hemen düşer, kapanış
     * executor'u beklemez). Jeton sayesinde eski çalıştırmanın geç gelen sonucu yazılmaz.
     */
    int releaseUnfinished() {
        int released = 0;
        for (Map.Entry<Long, Running> entry : Map.copyOf(runningJobs).entrySet()) {
            Running running = entry.getValue();
            try {
                if (TenantContext.asSystem(() -> scanJobRepository.release(entry.getKey(), running.token))) {
                    released++;
                }
            } catch (Exception e) {
                // DB'ye ulaşılamazsa iş kalp atışı zaman aşımıyla yine kurtarılır
                log.warn("Could not release scan job {} on shutdown: {}", entry.getKey(), e.getClass().getSimpleName());
            }
            Thread worker = running.worker;
            if (worker != null) {
                worker.interrupt();
            }
        }
        if (released > 0) {
            log.warn("Shutdown: returned {} unfinished scan job(s) to the queue", released);
        }
        return released;
    }

    @Override
    public boolean isRunning() {
        return lifecycleRunning;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    int inFlight() {
        return inFlight.get();
    }
}
