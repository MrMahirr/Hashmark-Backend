package dev.hashmark.common.config;

import dev.hashmark.common.metrics.AppMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadSheddingFilterTest {

    private SimpleMeterRegistry registry;
    private final AtomicInteger dbPending = new AtomicInteger();

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Gauge.builder("hikaricp.connections.pending", dbPending, AtomicInteger::get).register(registry);
    }

    private int status(LoadSheddingFilter filter, String method, String uri) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, uri), res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void underDbPressureOnlyExpensiveEndpointsAreShed() throws Exception {
        LoadSheddingFilter filter = new LoadSheddingFilter(true, 1, 100, registry, new AppMetrics(registry));
        dbPending.set(3);

        assertEquals(503, status(filter, "POST", "/scan/5"));
        assertEquals(503, status(filter, "GET", "/debts/export"));
        assertEquals(503, status(filter, "POST", "/repos/sync"));
        assertEquals(503, status(filter, "GET", "/debts/42/context"));
        // giriş ve okuma korunur
        assertEquals(200, status(filter, "GET", "/auth/callback"));
        assertEquals(200, status(filter, "POST", "/auth/refresh"));
        assertEquals(200, status(filter, "GET", "/debts"));
        assertEquals(200, status(filter, "GET", "/report/summary"));
        assertEquals(200, status(filter, "GET", "/scan/5/status"));
        assertEquals(4.0, registry.counter("hashmark.loadshed.rejected", "class", "expensive", "reason", "db_pool").count());
    }

    @Test
    void noPressureNoShedding() throws Exception {
        LoadSheddingFilter filter = new LoadSheddingFilter(true, 1, 100, registry, new AppMetrics(registry));
        assertEquals(200, status(filter, "POST", "/scan/5"));
    }

    @Test
    void disabledFilterNeverSheds() throws Exception {
        LoadSheddingFilter filter = new LoadSheddingFilter(false, 1, 100, registry, new AppMetrics(registry));
        dbPending.set(50);
        assertEquals(200, status(filter, "POST", "/scan/5"));
    }

    @Test
    void inflightThresholdTriggersShedding() {
        LoadSheddingFilter filter = new LoadSheddingFilter(true, 0, 10, registry, new AppMetrics(registry));
        assertEquals(null, filter.pressure(10));
        assertEquals("inflight", filter.pressure(11));
    }

    @Test
    void rejectionCarriesRetryAfterAndCode() throws Exception {
        LoadSheddingFilter filter = new LoadSheddingFilter(true, 1, 100, registry, new AppMetrics(registry));
        dbPending.set(1);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/scan/1"), res, new MockFilterChain());
        assertEquals("10", res.getHeader("Retry-After"));
        assertTrue(res.getContentAsString().contains("OVERLOADED"));
    }

    @Test
    void classification() {
        assertTrue(LoadSheddingFilter.isExpensive(new MockHttpServletRequest("POST", "/report/send-test")));
        assertFalse(LoadSheddingFilter.isExpensive(new MockHttpServletRequest("PATCH", "/debts/1")));
        assertFalse(LoadSheddingFilter.isExpensive(new MockHttpServletRequest("GET", "/debts/1")));
    }
}
