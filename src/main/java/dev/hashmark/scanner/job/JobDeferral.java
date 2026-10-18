package dev.hashmark.scanner.job;

import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;

import java.util.concurrent.ThreadLocalRandom;

/** Geçici hatada (GitHub kesintisi, rate-limit) işin ertelenme kuralı; tarama ve PR kontrolü ortak kullanır. */
public final class JobDeferral {

    private JobDeferral() {
    }

    public static boolean isDeferrable(ScanJob job, ScanProperties properties) {
        int deferrals = job.getDeferrals() == null ? 0 : job.getDeferrals();
        return deferrals < properties.getMaxDeferrals();
    }

    /**
     * Üstel geri çekilme (taban × 2^erteleme, üst sınırlı) + %0–25 jitter; GitHub'ın önerdiği bekleme
     * (Retry-After / rate-limit sıfırlanması) daha uzunsa o kullanılır.
     */
    public static long delaySeconds(ScanJob job, Long retryAfterSeconds, ScanProperties properties) {
        int deferrals = job.getDeferrals() == null ? 0 : job.getDeferrals();
        long backoff = Math.min(properties.getDeferMaxSeconds(),
                properties.getDeferBaseSeconds() << Math.min(deferrals, 20));
        long delay = Math.max(backoff, retryAfterSeconds == null ? 0 : retryAfterSeconds);
        return delay + ThreadLocalRandom.current().nextLong(delay / 4 + 1);
    }
}
