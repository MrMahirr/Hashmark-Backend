package dev.hashmark.common.metrics;

import dev.hashmark.scanner.repository.ScanJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/** Tarama kuyruğu göstergeleri; her Prometheus çekiminde veritabanından okunur (ucuz sayım sorguları). */
@Component
public class ScanQueueMetrics implements MeterBinder {

    private static final Logger log = LoggerFactory.getLogger(ScanQueueMetrics.class);

    private final ScanJobRepository jobs;

    public ScanQueueMetrics(ScanJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("hashmark.scan.queue.pending", () -> safe(() -> (double) jobs.countPending()))
                .description("Kuyrukta bekleyen (PENDING) tarama işleri").register(registry);
        Gauge.builder("hashmark.scan.queue.oldest.age.seconds", () -> safe(jobs::oldestPendingAgeSeconds))
                .description("En eski bekleyen işin yaşı (sn)").baseUnit("seconds").register(registry);
        Gauge.builder("hashmark.scan.running", () -> safe(() -> (double) jobs.countRunning()))
                .description("Çalışmakta olan (RUNNING) tarama işleri").register(registry);
    }

    private static double safe(Supplier<Double> reader) {
        try {
            return reader.get();
        } catch (Exception e) {
            log.warn("Kuyruk metriği okunamadı: {}", e.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
