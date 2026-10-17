package dev.hashmark.common.ratelimit;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.metrics.AppMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountRateLimiterTest {

    private final AtomicLong now = new AtomicLong(5_000_000);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private AccountRateLimiter limiter(int refresh, int login) {
        RateLimitProperties p = new RateLimitProperties();
        p.setRefreshPerAccountPerMinute(refresh);
        p.setLoginPerAccountPerMinute(login);
        return new AccountRateLimiter(p, new SlidingWindowLimiter(now::get), new AppMetrics(registry));
    }

    @Test
    void refreshIsLimitedPerAccount() {
        AccountRateLimiter limiter = limiter(2, 10);
        limiter.checkRefresh(1L);
        limiter.checkRefresh(1L);
        ApiException ex = assertThrows(ApiException.class, () -> limiter.checkRefresh(1L));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getHttpStatus());
        assertEquals("RATE_LIMITED", ex.getCode());
        assertTrue(ex.getRetryAfterSeconds() >= 1 && ex.getRetryAfterSeconds() <= 60);
        assertDoesNotThrow(() -> limiter.checkRefresh(2L), "başka hesap etkilenmez");

        now.addAndGet(60_001);
        assertDoesNotThrow(() -> limiter.checkRefresh(1L), "pencere kayınca yeniden izin");
        assertEquals(1.0, registry.counter("hashmark.ratelimit.rejected", "scope", "account_refresh").count());
    }

    @Test
    void loginIsLimitedPerGithubAccount() {
        AccountRateLimiter limiter = limiter(10, 1);
        limiter.checkLogin("42");
        assertThrows(ApiException.class, () -> limiter.checkLogin("42"));
        assertDoesNotThrow(() -> limiter.checkLogin("43"));
    }

    @Test
    void disabledLimiterNeverRejects() {
        AccountRateLimiter limiter = AccountRateLimiter.disabled(new AppMetrics(registry));
        for (int i = 0; i < 1000; i++) {
            limiter.checkRefresh(1L);
        }
    }

    @Test
    void windowKeysArePurgedUnderKeyFlood() {
        SlidingWindowLimiter sliding = new SlidingWindowLimiter(now::get);
        for (int i = 0; i < 10_050; i++) {
            sliding.tryAcquire("k" + i, 5, 1_000);
        }
        now.addAndGet(2_000);
        sliding.tryAcquire("fresh", 5, 1_000);
        assertTrue(sliding.keyCount() < 100, "süresi dolan anahtarlar temizlenmeli: " + sliding.keyCount());
    }
}
