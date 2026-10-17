package dev.hashmark.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uygulamaya özgü metrikler. Etiketler (label) sınırlı küme değerleriyle sınırlıdır; kullanıcı kimliği,
 * depo adı, token veya içerik asla etiket olarak eklenmez (kardinalite + gizlilik).
 */
@Component
public class AppMetrics {

    private final MeterRegistry registry;
    private final Counter refreshReuse;
    private final AtomicLong githubRateLimitRemaining = new AtomicLong(-1);

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.refreshReuse = Counter.builder("hashmark.auth.refresh.reuse.detected")
                .description("Yeniden kullanılan refresh token tespit sayısı (token ailesi iptal edildi)")
                .register(registry);
        Gauge.builder("hashmark.github.ratelimit.remaining", githubRateLimitRemaining, AtomicLong::get)
                .description("GitHub'ın bildirdiği son kalan istek hakkı (-1: henüz bilinmiyor)")
                .register(registry);
    }

    public void refreshReuseDetected() {
        refreshReuse.increment();
    }

    /** scope: "auth" veya "scan". */
    public void rateLimited(String scope) {
        Counter.builder("hashmark.ratelimit.rejected")
                .description("Uygulama hız sınırı nedeniyle reddedilen (429) istekler")
                .tag("scope", scope)
                .register(registry)
                .increment();
    }

    /** result: "done" veya "failed". */
    public void scanFinished(String result, Duration duration) {
        Timer.builder("hashmark.scan.duration")
                .description("Bir tarama işinin işlenme süresi")
                .tag("result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void githubRateLimitRemaining(long remaining) {
        githubRateLimitRemaining.set(remaining);
    }
}
