package dev.hashmark.common.config;

import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.common.ratelimit.RateLimitProperties;
import dev.hashmark.common.ratelimit.SlidingWindowLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Kayan pencereli, bellek içi hız sınırı matrisi (tek instance için; nginx {@code limit_req} tamamlar).
 * Her istek, eşleştiği <b>tüm</b> kurallardan geçmelidir; en dar (uç) kural önce denenir:
 *
 * <table>
 *   <tr><th>Kapsam</th><th>Eşleşme</th><th>Anahtar</th></tr>
 *   <tr><td>auth</td><td>{@code /auth/**}</td><td>IP</td></tr>
 *   <tr><td>scan</td><td>{@code POST /scan/**}</td><td>kullanıcı (yoksa IP)</td></tr>
 *   <tr><td>export</td><td>{@code GET /debts/export}</td><td>kullanıcı</td></tr>
 *   <tr><td>sync</td><td>{@code POST /repos/sync}</td><td>kullanıcı</td></tr>
 *   <tr><td>context</td><td>{@code GET /debts/{id}/context}</td><td>kullanıcı</td></tr>
 *   <tr><td>public</td><td>{@code /public/**}</td><td>IP</td></tr>
 *   <tr><td>webhook</td><td>{@code /webhooks/**}</td><td>yol (depo); IP tabanı uygulanmaz</td></tr>
 *   <tr><td>user</td><td>kimlikli her istek</td><td>kullanıcı</td></tr>
 *   <tr><td>ip</td><td>her istek</td><td>IP</td></tr>
 * </table>
 *
 * IP, {@code server.tomcat.remoteip} ile yalnızca <b>güvenilir proxy</b> listesinden gelen {@code X-Forwarded-For}
 * üzerinden çözülür (istemcinin kendi yazdığı başlık sahte IP üretemez). Hesap bazlı kimlik sınırları (refresh,
 * giriş) {@code AccountRateLimiter} içindedir.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final Pattern CONTEXT_PATH = Pattern.compile("^/debts/[^/]+/context$");

    private final RateLimitProperties props;
    private final SlidingWindowLimiter limiter;
    private final AppMetrics metrics;

    public RateLimitFilter(RateLimitProperties props, AppMetrics metrics) {
        this(props, System::currentTimeMillis, metrics);
    }

    RateLimitFilter(RateLimitProperties props, LongSupplier clock, AppMetrics metrics) {
        this.props = props;
        this.limiter = new SlidingWindowLimiter(clock);
        this.metrics = metrics;
    }

    /** Yalnızca auth + scan kuralları açık (diğerleri 0); eski testler için. */
    RateLimitFilter(int authPerMinute, int scanPerMinute, LongSupplier clock) {
        this(authPerMinute, scanPerMinute, clock, new AppMetrics(new SimpleMeterRegistry()));
    }

    RateLimitFilter(int authPerMinute, int scanPerMinute, LongSupplier clock, AppMetrics metrics) {
        this(onlyAuthAndScan(authPerMinute, scanPerMinute), clock, metrics);
    }

    private static RateLimitProperties onlyAuthAndScan(int auth, int scan) {
        RateLimitProperties p = new RateLimitProperties();
        p.setAuthPerMinute(auth);
        p.setScanPerMinute(scan);
        p.setExportPerMinute(0);
        p.setSyncPerMinute(0);
        p.setContextPerMinute(0);
        p.setPublicPerMinute(0);
        p.setWebhookPerMinute(0);
        p.setUserPerMinute(0);
        p.setIpPerMinute(0);
        return p;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = "ip" + request.getRemoteAddr();
        Long userId = currentUserId();
        String subject = userId != null ? "u" + userId : ip;

        if (path.startsWith("/auth/") && rejected("auth", ip, props.getAuthPerMinute(), response)) {
            return;
        }
        if ("POST".equalsIgnoreCase(method) && path.startsWith("/scan")
                && rejected("scan", subject, props.getScanPerMinute(), response)) {
            return;
        }
        if ("GET".equalsIgnoreCase(method) && "/debts/export".equals(path)
                && rejected("export", subject, props.getExportPerMinute(), response)) {
            return;
        }
        if ("POST".equalsIgnoreCase(method) && "/repos/sync".equals(path)
                && rejected("sync", subject, props.getSyncPerMinute(), response)) {
            return;
        }
        if ("GET".equalsIgnoreCase(method) && CONTEXT_PATH.matcher(path).matches()
                && rejected("context", subject, props.getContextPerMinute(), response)) {
            return;
        }
        if (path.startsWith("/public/") && rejected("public", ip, props.getPublicPerMinute(), response)) {
            return;
        }
        if (path.startsWith("/webhooks/")) {
            // GitHub'ın paylaşımlı IP'leri tüm depoların teslimatlarını taşır: IP tabanı yerine depo adresi (yol)
            if (!rejected("webhook", path, props.getWebhookPerMinute(), response)) {
                chain.doFilter(request, response);
            }
            return;
        }
        if (userId != null && rejected("user", subject, props.getUserPerMinute(), response)) {
            return;
        }
        if (rejected("ip", ip, props.getIpPerMinute(), response)) {
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean rejected(String scope, String subject, int limit, HttpServletResponse response) throws IOException {
        long retryAfterMs = limiter.tryAcquire(scope + ":" + subject, limit, WINDOW_MS);
        if (retryAfterMs <= 0) {
            return false;
        }
        metrics.rateLimited(scope);
        reject(response, retryAfterMs);
        return true;
    }

    /** Geriye uyumluluk (testler): tek anahtar için pencere denemesi. */
    long tryAcquire(String key, int limit) {
        return limiter.tryAcquire(key, limit, WINDOW_MS);
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return userId;
        }
        return null;
    }

    private static void reject(HttpServletResponse response, long retryAfterMs) throws IOException {
        long seconds = Math.max(1, (retryAfterMs + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(seconds));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\",\"code\":\"RATE_LIMITED\","
                + "\"message\":\"Too many requests. Retry in " + seconds + "s.\"}");
    }
}
