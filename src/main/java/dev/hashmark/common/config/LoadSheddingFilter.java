package dev.hashmark.common.config;

import dev.hashmark.common.metrics.AppMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Öncelikli yük atma (plan Faz 3). Sistem zorlanınca önce <b>pahalı</b> uçlar 503 alır; giriş ve okuma uçları
 * korunur (hiçbir zaman burada reddedilmez). Pahalı uçlar: tarama başlatma, dışa aktarma, GitHub senkronu,
 * canlı bağlam, test e-postası.
 *
 * <p>Baskı sinyalleri (biri yeterli):
 * <ul>
 *   <li>DB bağlantı havuzunda bağlantı <b>bekleyen</b> iş parçacığı sayısı eşiği aştı ({@code hikaricp.connections.pending});</li>
 *   <li>bu instance'ta eşzamanlı işlenen istek sayısı eşiği aştı.</li>
 * </ul>
 * Pahalı istek havuzu kısa süreli değil uzun süreli tutar; baskı altındayken onları reddetmek, bekleyen okuma ve
 * giriş isteklerinin havuza erişmesini sağlar.
 */
public class LoadSheddingFilter extends OncePerRequestFilter {

    private static final Pattern CONTEXT_PATH = Pattern.compile("^/debts/[^/]+/context$");

    private final boolean enabled;
    private final int dbPendingThreshold;
    private final int maxInflight;
    private final MeterRegistry registry;
    private final AppMetrics metrics;
    private final AtomicInteger inflight = new AtomicInteger();

    public LoadSheddingFilter(boolean enabled, int dbPendingThreshold, int maxInflight, MeterRegistry registry,
                              AppMetrics metrics) {
        this.enabled = enabled;
        this.dbPendingThreshold = dbPendingThreshold;
        this.maxInflight = maxInflight;
        this.registry = registry;
        this.metrics = metrics;
        Gauge.builder("hashmark.http.inflight", inflight, AtomicInteger::get)
                .description("Bu instance'ta eşzamanlı işlenen API istekleri (yük atma sinyali)")
                .register(registry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int current = inflight.incrementAndGet();
        try {
            if (enabled && isExpensive(request)) {
                String reason = pressure(current);
                if (reason != null) {
                    metrics.loadShed("expensive", reason);
                    reject(response);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            inflight.decrementAndGet();
        }
    }

    /** Baskı yoksa null; varsa nedeni ("db_pool" / "inflight"). */
    String pressure(int currentInflight) {
        if (maxInflight > 0 && currentInflight > maxInflight) {
            return "inflight";
        }
        if (dbPendingThreshold > 0) {
            Gauge pending = registry.find("hikaricp.connections.pending").gauge();
            if (pending != null && pending.value() >= dbPendingThreshold) {
                return "db_pool";
            }
        }
        return null;
    }

    static boolean isExpensive(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(method)) {
            return path.startsWith("/scan") || "/repos/sync".equals(path) || "/report/send-test".equals(path);
        }
        if ("GET".equalsIgnoreCase(method)) {
            return "/debts/export".equals(path) || CONTEXT_PATH.matcher(path).matches();
        }
        return false;
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(503);
        response.setHeader("Retry-After", "10");
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"status\":503,\"error\":\"Service Unavailable\",\"code\":\"OVERLOADED\","
                + "\"message\":\"The server is under heavy load. This action is paused; please retry shortly.\"}");
    }
}
