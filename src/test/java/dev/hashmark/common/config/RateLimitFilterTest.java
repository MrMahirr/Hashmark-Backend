package dev.hashmark.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private MockHttpServletResponse call(RateLimitFilter filter, String method, String uri, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private int status(RateLimitFilter filter, String method, String uri, String ip) throws Exception {
        return call(filter, method, uri, ip).getStatus();
    }

    @Test
    void blocksAuthEndpointsAfterLimitPerIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(3, 10, now::get);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, status(filter, "GET", "/auth/github", "1.1.1.1"));
        }
        assertEquals(429, status(filter, "GET", "/auth/github", "1.1.1.1"));
        // başka IP etkilenmez
        assertEquals(200, status(filter, "GET", "/auth/github", "2.2.2.2"));
    }

    @Test
    void windowSlidesAndAllowsAgain() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 10, now::get);
        assertEquals(200, status(filter, "POST", "/auth/refresh", "1.1.1.1"));
        assertEquals(429, status(filter, "POST", "/auth/refresh", "1.1.1.1"));
        now.addAndGet(61_000);
        assertEquals(200, status(filter, "POST", "/auth/refresh", "1.1.1.1"));
    }

    @Test
    void rejectionCarriesRetryAfter() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1, 10, now::get);
        status(filter, "GET", "/auth/github", "1.1.1.1");
        MockHttpServletResponse res = call(filter, "GET", "/auth/github", "1.1.1.1");
        assertEquals(429, res.getStatus());
        assertNotNull(res.getHeader("Retry-After"));
        assertTrue(Integer.parseInt(res.getHeader("Retry-After")) >= 1);
    }

    @Test
    void onlyScanPostsAreLimitedAndOtherPathsAreFree() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(30, 1, now::get);
        assertEquals(200, status(filter, "POST", "/scan/5", "1.1.1.1"));
        assertEquals(429, status(filter, "POST", "/scan/5", "1.1.1.1"));
        assertEquals(200, status(filter, "GET", "/scan/5/status", "1.1.1.1"));
        assertEquals(200, status(filter, "GET", "/repos", "1.1.1.1"));
    }

    private MockHttpServletResponse callAs(RateLimitFilter filter, String method, String uri, String ip, Long userId)
            throws Exception {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                userId == null ? null : new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        userId, null, java.util.List.of()));
        try {
            return call(filter, method, uri, ip);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    private static dev.hashmark.common.ratelimit.RateLimitProperties matrix() {
        dev.hashmark.common.ratelimit.RateLimitProperties p = new dev.hashmark.common.ratelimit.RateLimitProperties();
        p.setExportPerMinute(2);
        p.setSyncPerMinute(1);
        p.setContextPerMinute(2);
        p.setPublicPerMinute(2);
        p.setUserPerMinute(5);
        p.setIpPerMinute(8);
        return p;
    }

    @Test
    void expensiveEndpointsAreLimitedPerUserNotPerIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(matrix(), now::get,
                new dev.hashmark.common.metrics.AppMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        assertEquals(200, callAs(filter, "GET", "/debts/export", "1.1.1.1", 7L).getStatus());
        assertEquals(200, callAs(filter, "GET", "/debts/export", "9.9.9.9", 7L).getStatus());
        assertEquals(429, callAs(filter, "GET", "/debts/export", "5.5.5.5", 7L).getStatus(), "IP değişse de kullanıcı aynı");
        assertEquals(200, callAs(filter, "GET", "/debts/export", "1.1.1.1", 8L).getStatus(), "başka kullanıcı etkilenmez");

        assertEquals(200, callAs(filter, "POST", "/repos/sync", "1.1.1.1", 7L).getStatus());
        assertEquals(429, callAs(filter, "POST", "/repos/sync", "1.1.1.1", 7L).getStatus());
        assertEquals(200, callAs(filter, "GET", "/debts/3/context", "1.1.1.1", 9L).getStatus());
        assertEquals(200, callAs(filter, "GET", "/debts/4/context", "1.1.1.1", 9L).getStatus());
        assertEquals(429, callAs(filter, "GET", "/debts/5/context", "1.1.1.1", 9L).getStatus());
    }

    @Test
    void generalUserAndIpFloorsApply() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(matrix(), now::get,
                new dev.hashmark.common.metrics.AppMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        for (int i = 0; i < 5; i++) {
            assertEquals(200, callAs(filter, "GET", "/debts", "2.2.2.2", 11L).getStatus());
        }
        assertEquals(429, callAs(filter, "GET", "/debts", "3.3.3.3", 11L).getStatus(), "kullanıcı tabanı");
        // IP tabanı: aynı IP'den farklı kullanıcılar (ilk 5'i zaten 2.2.2.2'den sayıldı)
        assertEquals(200, callAs(filter, "GET", "/debts", "2.2.2.2", 12L).getStatus());
        assertEquals(200, callAs(filter, "GET", "/debts", "2.2.2.2", 13L).getStatus());
        assertEquals(200, callAs(filter, "GET", "/debts", "2.2.2.2", 14L).getStatus());
        assertEquals(429, callAs(filter, "GET", "/debts", "2.2.2.2", 15L).getStatus(), "IP tabanı (8/dk)");
    }

    @Test
    void publicEndpointsAreLimitedPerIp() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(matrix(), now::get,
                new dev.hashmark.common.metrics.AppMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        assertEquals(200, status(filter, "GET", "/public/stats", "4.4.4.4"));
        assertEquals(200, status(filter, "GET", "/public/stats", "4.4.4.4"));
        MockHttpServletResponse res = call(filter, "GET", "/public/stats", "4.4.4.4");
        assertEquals(429, res.getStatus());
        assertTrue(res.getContentAsString().contains("RATE_LIMITED"));
    }

    @Test
    void countsRejectionsPerScope() throws Exception {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        RateLimitFilter filter = new RateLimitFilter(1, 1, now::get, new dev.hashmark.common.metrics.AppMetrics(registry));
        status(filter, "GET", "/auth/github", "1.1.1.1");
        status(filter, "GET", "/auth/github", "1.1.1.1");
        status(filter, "POST", "/scan/5", "1.1.1.1");
        status(filter, "POST", "/scan/5", "1.1.1.1");
        status(filter, "POST", "/scan/5", "1.1.1.1");
        assertEquals(1.0, registry.counter("hashmark.ratelimit.rejected", "scope", "auth").count());
        assertEquals(2.0, registry.counter("hashmark.ratelimit.rejected", "scope", "scan").count());
    }
}
