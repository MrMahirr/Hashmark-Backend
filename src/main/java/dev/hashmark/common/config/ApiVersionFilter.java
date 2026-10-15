package dev.hashmark.common.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Tüm uçlar {@code /v1/...} önekiyle de erişilebilir: {@code /v1/debts} ile {@code /debts} aynı işi görür.
 * Önek, Spring Security zincirinden önce kaldırıldığı için yetkilendirme kuralları iki yol için de geçerlidir.
 * Mevcut istemciler (öneksiz yollar) bozulmaz; yeni istemciler {@code /v1} kullanmalıdır.
 * Springdoc şeması {@code /v1/api-docs} olduğundan o yol dokunulmadan bırakılır.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiVersionFilter extends OncePerRequestFilter {

    static final String PREFIX = "/v1";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String uri = request.getRequestURI();
        String path = uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;

        if (path.startsWith(PREFIX + "/") && !path.startsWith(PREFIX + "/api-docs")) {
            String stripped = path.substring(PREFIX.length());
            chain.doFilter(new StrippedRequest(request, contextPath, stripped), response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static final class StrippedRequest extends HttpServletRequestWrapper {

        private final String contextPath;
        private final String path;

        StrippedRequest(HttpServletRequest request, String contextPath, String path) {
            super(request);
            this.contextPath = contextPath;
            this.path = path;
        }

        @Override
        public String getRequestURI() {
            return contextPath + path;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer original = super.getRequestURL();
            String originalPath = super.getRequestURI();
            int index = original.lastIndexOf(originalPath);
            if (index >= 0) {
                original.replace(index, index + originalPath.length(), getRequestURI());
            }
            return original;
        }

        @Override
        public String getServletPath() {
            return path;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
