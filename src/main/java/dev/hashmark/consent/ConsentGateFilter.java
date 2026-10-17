package dev.hashmark.consent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Kapı açıkken (consent.enforced) onaysız kullanıcı veri işleyen hiçbir ucu kullanamaz:
 * {@code 403 {"code":"CONSENT_REQUIRED"}}. Yalnızca onay vermek, durumunu görmek, bağlantıyı kesmek ve hesabı
 * silmek serbesttir (plan 5.10). JWT filtresinden SONRA çalışır; Spring bean'i değildir (servlet zincirine iki
 * kez eklenmesin diye SecurityConfig kurar).
 */
public class ConsentGateFilter extends OncePerRequestFilter {

    private final ConsentService consent;

    public ConsentGateFilter(ConsentService consent) {
        this.consent = consent;
    }

    static boolean isExempt(String method, String path) {
        if (path.startsWith("/auth/") || path.startsWith("/public/") || path.startsWith("/actuator/")) {
            return true;
        }
        return switch (path) {
            case "/user/me", "/user/consent" -> true;
            case "/user/account", "/user/github-connection" -> "DELETE".equalsIgnoreCase(method);
            default -> false;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (consent.isEnforced() && !isExempt(request.getMethod(), request.getRequestURI())) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long userId && consent.isProcessingBlocked(userId)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\",\"code\":\""
                        + ConsentService.CONSENT_REQUIRED + "\",\"message\":\"Consent is required before data is processed\"}");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
