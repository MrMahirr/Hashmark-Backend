package dev.hashmark.common.ratelimit;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.metrics.AppMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Kimlik uçlarında <b>hesap bazlı</b> sınır: IP sınırı, saldırgan IP değiştirdiğinde (botnet, proxy) işe yaramaz.
 * Refresh için kullanıcı kimliği (imzası doğrulanmış token'dan), giriş için GitHub hesap kimliği anahtar alınır.
 */
@Component
public class AccountRateLimiter {

    private static final long WINDOW_MS = 60_000L;

    private final RateLimitProperties props;
    private final SlidingWindowLimiter limiter;
    private final AppMetrics metrics;

    @Autowired
    public AccountRateLimiter(RateLimitProperties props, AppMetrics metrics) {
        this(props, new SlidingWindowLimiter(), metrics);
    }

    AccountRateLimiter(RateLimitProperties props, SlidingWindowLimiter limiter, AppMetrics metrics) {
        this.props = props;
        this.limiter = limiter;
        this.metrics = metrics;
    }

    /** Sınırsız örnek (testler ve elle kurulan servisler). */
    public static AccountRateLimiter disabled(AppMetrics metrics) {
        RateLimitProperties p = new RateLimitProperties();
        p.setRefreshPerAccountPerMinute(0);
        p.setLoginPerAccountPerMinute(0);
        return new AccountRateLimiter(p, metrics);
    }

    public void checkRefresh(Long userId) {
        check("account_refresh", "u" + userId, props.getRefreshPerAccountPerMinute());
    }

    public void checkLogin(String githubId) {
        check("account_login", "gh" + githubId, props.getLoginPerAccountPerMinute());
    }

    private void check(String scope, String key, int limit) {
        long retryAfterMs = limiter.tryAcquire(scope + ":" + key, limit, WINDOW_MS);
        if (retryAfterMs > 0) {
            metrics.rateLimited(scope);
            throw ApiException.tooManyRequests("RATE_LIMITED", "Too many requests for this account. Please try again shortly",
                    Math.max(1, (retryAfterMs + 999) / 1000));
        }
    }
}
