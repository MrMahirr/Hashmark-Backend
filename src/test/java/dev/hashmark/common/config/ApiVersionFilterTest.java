package dev.hashmark.common.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ApiVersionFilterTest {

    private final ApiVersionFilter filter = new ApiVersionFilter();

    private HttpServletRequest run(MockHttpServletRequest request) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return (HttpServletRequest) chain.getRequest();
    }

    private MockHttpServletRequest request(String contextPath, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", contextPath + path);
        request.setContextPath(contextPath);
        request.setServletPath(path);
        return request;
    }

    @Test
    void stripsVersionPrefix() throws Exception {
        HttpServletRequest forwarded = run(request("", "/v1/debts"));

        assertEquals("/debts", forwarded.getRequestURI());
        assertEquals("/debts", forwarded.getServletPath());
        assertNull(forwarded.getPathInfo());
    }

    @Test
    void stripsPrefixFromNestedPathsAndKeepsContextPath() throws Exception {
        HttpServletRequest forwarded = run(request("/app", "/v1/auth/github"));

        assertEquals("/app/auth/github", forwarded.getRequestURI());
        assertEquals("/auth/github", forwarded.getServletPath());
    }

    @Test
    void leavesUnversionedPathsUntouched() throws Exception {
        MockHttpServletRequest original = request("", "/debts");

        assertSame(original, run(original));
    }

    @Test
    void doesNotTouchSpringdocSpecPath() throws Exception {
        MockHttpServletRequest original = request("", "/v1/api-docs/swagger-config");

        assertSame(original, run(original));
    }

    @Test
    void doesNotMatchLookalikePrefixes() throws Exception {
        MockHttpServletRequest original = request("", "/v10/debts");

        assertSame(original, run(original));
    }
}
