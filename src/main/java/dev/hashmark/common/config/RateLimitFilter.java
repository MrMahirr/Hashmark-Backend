package dev.hashmark.common.config;

import dev.hashmark.common.metrics.AppMetrics;
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
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Kayan pencereli, bellek içi hız sınırlayıcı (tek instance için; nginx `limit_req` tamamlar).
 *
 * <ul>
 *   <li>`/auth/**`: istemci IP'sine göre (brute-force / kimlik uçlarının kötüye kullanımı)</li>
 *   <li>`POST /scan/**`: giriş yapmış kullanıcıya göre (GitHub kotasını tüketmeyi önler), yoksa IP'ye göre</li>
 * </ul>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MS = 60_000L;
    private static final int PURGE_THRESHOLD = 10_000;

    private final int authPerMinute;
    private final int scanPerMinute;
    private final LongSupplier clock;
    private final AppMetrics metrics;
    private final Map<String, ArrayDeque<Long>> hits = new ConcurrentHashMap<>();

    public RateLimitFilter(int authPerMinute, int scanPerMinute, AppMetrics metrics) {
        this(authPerMinute, scanPerMinute, System::currentTimeMillis, metrics);
    }

    RateLimitFilter(int authPerMinute, int scanPerMinute, LongSupplier clock) {
        this(authPerMinute, scanPerMinute, clock, new AppMetrics(new SimpleMeterRegistry()));
    }

    RateLimitFilter(int authPerMinute, int scanPerMinute, LongSupplier clock, AppMetrics metrics) {
        this.authPerMinute = authPerMinute;
        this.scanPerMinute = scanPerMinute;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String key = null;
        String scope = null;
        int limit = 0;

        if (path.startsWith("/auth/")) {
            key = "auth:" + request.getRemoteAddr();
            scope = "auth";
            limit = authPerMinute;
        } else if ("POST".equalsIgnoreCase(request.getMethod()) && path.startsWith("/scan")) {
            key = "scan:" + principalOrIp(request);
            scope = "scan";
            limit = scanPerMinute;
        }

        if (key != null && limit > 0) {
            long retryAfterMs = tryAcquire(key, limit);
            if (retryAfterMs > 0) {
                metrics.rateLimited(scope);
                reject(response, retryAfterMs);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** İzin varsa 0; aksi halde pencerenin açılmasına kalan süre (ms). */
    long tryAcquire(String key, int limit) {
        long now = clock.getAsLong();
        if (hits.size() > PURGE_THRESHOLD) {
            hits.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().isEmpty() || now - e.getValue().peekLast() > WINDOW_MS;
                }
            });
        }
        ArrayDeque<Long> window = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() >= WINDOW_MS) {
                window.pollFirst();
            }
            if (window.size() >= limit) {
                return Math.max(1, WINDOW_MS - (now - window.peekFirst()));
            }
            window.addLast(now);
            return 0;
        }
    }

    private static String principalOrIp(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof Long userId) {
            return "u" + userId;
        }
        return "ip" + request.getRemoteAddr();
    }

    private static void reject(HttpServletResponse response, long retryAfterMs) throws IOException {
        long seconds = Math.max(1, (retryAfterMs + 999) / 1000);
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(seconds));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":429,\"error\":\"Too Many Requests\","
                + "\"message\":\"Too many requests. Retry in " + seconds + "s.\"}");
    }
}
