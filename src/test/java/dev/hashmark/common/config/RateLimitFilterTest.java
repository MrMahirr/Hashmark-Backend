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
