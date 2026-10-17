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

    /** table: "scan_jobs", "scan_snapshots" veya "notifications". */
    public void retentionDeleted(String table, int rows) {
        Counter.builder("hashmark.retention.deleted")
                .description("Saklama politikası gereği silinen satırlar")
                .tag("table", table)
                .register(registry)
                .increment(rows);
    }

    /** kind: "statement" (statement_timeout) veya "lock" (lock_timeout / kilit alınamadı). */
    public void dbTimeout(String kind) {
        Counter.builder("hashmark.db.timeout")
                .description("DB zaman aşımı nedeniyle 503 dönen istekler")
                .tag("kind", kind)
                .register(registry)
                .increment();
    }

    /** Anahtar dönüşü: result "ok" (yeniden şifrelendi) veya "failed" (çözülemedi). */
    public void reencrypted(String result, int count) {
        Counter.builder("hashmark.crypto.reencrypted")
                .description("Geçerli anahtarla yeniden şifrelenen değerler")
                .tag("result", result)
                .register(registry)
                .increment(count);
    }

    /** Geçici hata nedeniyle FAILED yerine ertelenen tarama işleri. */
    public void scanDeferred() {
        Counter.builder("hashmark.scan.deferred")
                .description("Geçici hata (GitHub kesintisi, rate-limit) nedeniyle ertelenen tarama işleri")
                .register(registry)
                .increment();
    }

    /** kind: "active", "daily", "repos", "repo_size" veya "queue_full". */
    public void scanQuotaRejected(String kind) {
        Counter.builder("hashmark.scan.quota.rejected")
                .description("Kullanıcı kotası ya da kuyruk üst sınırı nedeniyle reddedilen tarama/takip istekleri")
                .tag("kind", kind)
                .register(registry)
                .increment();
    }

    /** cls: yük atılan istek sınıfı ("expensive"). reason: "db_pool", "inflight". */
    public void loadShed(String cls, String reason) {
        Counter.builder("hashmark.loadshed.rejected")
                .description("Aşırı yükte önceliklendirme nedeniyle 503 dönen istekler")
                .tag("class", cls)
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    public void githubRateLimitRemaining(long remaining) {
        githubRateLimitRemaining.set(remaining);
    }
}
