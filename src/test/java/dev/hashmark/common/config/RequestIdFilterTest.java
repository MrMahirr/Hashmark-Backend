package dev.hashmark.common.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    private String run(String incoming, AtomicReference<String> seenInChain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/repos");
        if (incoming != null) {
            req.addHeader(RequestIdFilter.HEADER, incoming);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest rq, jakarta.servlet.ServletResponse rs) {
                seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY));
            }
        });
        return res.getHeader(RequestIdFilter.HEADER);
    }

    @Test
    void reusesSafeIncomingId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        assertEquals("abc-12345678", run("abc-12345678", seen));
        assertEquals("abc-12345678", seen.get());
        assertNull(MDC.get(RequestIdFilter.MDC_KEY), "MDC istek bitince temizlenmeli");
    }

    @Test
    void generatesIdWhenMissing() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String id = run(null, seen);
        assertEquals(36, id.length());
        assertEquals(id, seen.get());
    }

    @Test
    void rejectsUnsafeIncomingId() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        String evil = "x\"}\n{\"injected\":true";
        String id = run(evil, seen);
        assertNotEquals(evil, id);
        assertEquals(36, id.length());
    }
}
