package dev.hashmark.scanner.job;

import dev.hashmark.scanner.repository.ScanJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Taramalar bellekte (@Async) çalışır; uygulama yeniden başlayınca yarım kalan RUNNING kayıtlar,
 * kullanıcının yeni tarama başlatmasını engellemesin diye başarısız işaretlenir.
 * Tek instance varsayımı geçerlidir; çoklu instance'ta kalıcı bir iş kuyruğu gerekir.
 */
@Component
public class ScanJobStartupCleanup {

    private static final Logger log = LoggerFactory.getLogger(ScanJobStartupCleanup.class);

    private final ScanJobRepository scanJobRepository;

    public ScanJobStartupCleanup(ScanJobRepository scanJobRepository) {
        this.scanJobRepository = scanJobRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedJobs() {
        int count = scanJobRepository.failAllRunningJobs();
        if (count > 0) {
            log.warn("Marked {} interrupted scan job(s) as FAILED", count);
        }
    }
}
