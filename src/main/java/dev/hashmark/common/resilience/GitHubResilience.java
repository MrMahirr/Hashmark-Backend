package dev.hashmark.common.resilience;

import dev.hashmark.common.exception.ApiException;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * GitHub'a giden her çağrının önündeki koruma (plan Faz 3):
 * <ul>
 *   <li><b>Bulkhead:</b> iki ayrı, sınırlı şerit. Kullanıcı isteği yolu (giriş, repo listesi, bağlam) ile tarama
 *       işçileri birbirinin kapasitesini tüketemez. Şerit doluysa kısa beklemeden sonra 503.</li>
 *   <li><b>Circuit breaker:</b> GitHub kesintisinde (ağ hatası, zaman aşımı, 5xx, aşırı yavaşlık) devre açılır;
 *       açıkken çağrılar GitHub'a hiç gitmeden hemen 503 alır, iş parçacıkları ve havuzlar tükenmez.</li>
 * </ul>
 * 4xx yanıtlar (yetki, bulunamadı, rate-limit) GitHub'ın <i>ayakta</i> olduğunu gösterir; hata sayılmaz.
 * Devre tek ve ortaktır: GitHub düştüyse hem giriş hem tarama için düşmüştür.
 */
@Component
public class GitHubResilience {

    private static final Logger log = LoggerFactory.getLogger(GitHubResilience.class);

    public static final String CODE_UNAVAILABLE = "GITHUB_UNAVAILABLE";
    public static final String CODE_BUSY = "GITHUB_BUSY";

    public enum Lane { INTERACTIVE, SCAN }

    private final CircuitBreaker breaker;
    private final Bulkhead interactive;
    private final Bulkhead scan;
    private final long retryAfterSeconds;
    private final MeterRegistry meterRegistry;

    public GitHubResilience(GitHubResilienceProperties props, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.retryAfterSeconds = Math.max(1, props.getOpenStateWaitMs() / 1000);

        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(props.getSlidingWindowSize())
                .minimumNumberOfCalls(props.getMinimumNumberOfCalls())
                .failureRateThreshold(props.getFailureRateThreshold())
                .slowCallDurationThreshold(Duration.ofMillis(props.getSlowCallDurationMs()))
                .slowCallRateThreshold(props.getSlowCallRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(props.getOpenStateWaitMs()))
                .permittedNumberOfCallsInHalfOpenState(props.getPermittedCallsInHalfOpen())
                .build());
        this.breaker = breakers.circuitBreaker("github");
        this.breaker.getEventPublisher().onStateTransition(e ->
                log.warn("GitHub circuit breaker: {}", e.getStateTransition()));

        BulkheadRegistry bulkheads = BulkheadRegistry.ofDefaults();
        this.interactive = bulkheads.bulkhead("github-interactive", BulkheadConfig.custom()
                .maxConcurrentCalls(props.getInteractiveMaxConcurrent())
                .maxWaitDuration(Duration.ofMillis(props.getInteractiveMaxWaitMs()))
                .build());
        this.scan = bulkheads.bulkhead("github-scan", BulkheadConfig.custom()
                .maxConcurrentCalls(props.getScanMaxConcurrent())
                .maxWaitDuration(Duration.ofMillis(props.getScanMaxWaitMs()))
                .build());

        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meterRegistry);
        TaggedBulkheadMetrics.ofBulkheadRegistry(bulkheads).bindTo(meterRegistry);
    }

    /** Testler ve elle kurulan istemciler için varsayılan ayarlı örnek. */
    public static GitHubResilience defaults() {
        return new GitHubResilience(new GitHubResilienceProperties(), new SimpleMeterRegistry());
    }

    /**
     * Tek bir GitHub çağrısı için izin alır. Devre açıksa ya da şerit doluysa {@link ApiException} (503) fırlatır.
     * Dönen izin mutlaka kapatılmalıdır (try-with-resources); sonuç {@link Permit#success()} /
     * {@link Permit#failure(Throwable)} ile bildirilir, bildirilmezse izin sonuçsuz bırakılır.
     */
    public Permit acquire(Lane lane) {
        if (!breaker.tryAcquirePermission()) {
            rejected(lane, "circuit_open");
            throw ApiException.serviceUnavailable(CODE_UNAVAILABLE,
                    "GitHub is currently unavailable. Please try again shortly", retryAfterSeconds);
        }
        Bulkhead bulkhead = lane == Lane.INTERACTIVE ? interactive : scan;
        if (!bulkhead.tryAcquirePermission()) {
            breaker.releasePermission();
            rejected(lane, "bulkhead_full");
            throw ApiException.serviceUnavailable(CODE_BUSY,
                    "Too many GitHub requests in progress. Please try again shortly", 2);
        }
        return new Permit(bulkhead);
    }

    /**
     * RestTemplate ile yapılan kullanıcı yolu çağrısı. Ağ hatası/zaman aşımı ve 5xx devreye hata olarak yazılır ve
     * 502'ye çevrilir (eskiden 500 dönüyordu); 4xx GitHub'ın ayakta olduğunu gösterir, olduğu gibi yükselir.
     * Burada yeniden deneme yoktur: yük patlamasında tekrar denemek yükü katlar, kullanıcı tekrar dener.
     */
    public <T> T callInteractive(Supplier<T> call) {
        try (Permit permit = acquire(Lane.INTERACTIVE)) {
            try {
                T result = call.get();
                permit.success();
                return result;
            } catch (ResourceAccessException | HttpServerErrorException e) {
                permit.failure(e);
                throw ApiException.badGateway("Could not reach GitHub: " + e.getClass().getSimpleName());
            } catch (RuntimeException e) {
                permit.success();
                throw e;
            }
        }
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    CircuitBreaker breaker() {
        return breaker;
    }

    private void rejected(Lane lane, String reason) {
        Counter.builder("hashmark.github.rejected")
                .description("GitHub'a gitmeden reddedilen çağrılar (devre açık / şerit dolu)")
                .tag("lane", lane.name().toLowerCase())
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    /** Tek çağrılık izin. Sonuç en fazla bir kez kaydedilir. */
    public final class Permit implements AutoCloseable {

        private final Bulkhead bulkhead;
        private final long startNanos = System.nanoTime();
        private boolean recorded;

        private Permit(Bulkhead bulkhead) {
            this.bulkhead = bulkhead;
        }

        /** GitHub yanıt verdi (2xx/3xx/4xx). */
        public void success() {
            if (!recorded) {
                recorded = true;
                breaker.onSuccess(elapsed(), TimeUnit.NANOSECONDS);
            }
        }

        /** GitHub'a ulaşılamadı, zaman aşımı ya da 5xx. */
        public void failure(Throwable cause) {
            if (!recorded) {
                recorded = true;
                breaker.onError(elapsed(), TimeUnit.NANOSECONDS, cause);
            }
        }

        private long elapsed() {
            return System.nanoTime() - startNanos;
        }

        @Override
        public void close() {
            if (!recorded) {
                recorded = true;
                breaker.releasePermission();
            }
            bulkhead.onComplete();
        }
    }
}
