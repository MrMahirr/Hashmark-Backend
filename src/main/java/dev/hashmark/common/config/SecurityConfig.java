package dev.hashmark.common.config;

import dev.hashmark.common.metrics.AppMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /** Swagger yalnızca bilinçli olarak açılırsa (geliştirme/staging) erişilebilir; prod'da kapalıdır. */
    @Value("${springdoc.swagger-ui.enabled:false}")
    private boolean swaggerEnabled;

    /** Actuator (health + prometheus) bu portta; yalnızca iç ağdan erişilir, nginx/compose yayınlamaz. */
    @Value("${management.server.port:-1}")
    private int managementPort;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${rate-limit.auth-per-minute:30}")
    private int authPerMinute;

    @Value("${rate-limit.scan-per-minute:10}")
    private int scanPerMinute;

    private final AppMetrics metrics;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, AppMetrics metrics) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.metrics = metrics;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Kimlik doğrulama Authorization başlığıyla (çerez yok) yapıldığı için CSRF yüzeyi yoktur.
            // Çerez tabanlı oturuma geçilirse burası yeniden açılmalıdır.
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> {}) // Handled by CorsConfig
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers
                .contentTypeOptions(opts -> {})
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000))
                .referrerPolicy(ref -> ref.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // API yalnızca JSON döner; hiçbir kaynak yüklemesine ihtiyaç duymaz.
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    swaggerEnabled
                        ? "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self' 'unsafe-inline'"
                        : "default-src 'none'; frame-ancestors 'none'"))
                .addHeaderWriter(new org.springframework.security.web.header.writers.StaticHeadersWriter("Permissions-Policy", "camera=(), microphone=(), geolocation=()"))
            )
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/auth/**", "/public/**").permitAll();
                // Ayrı yönetim portundaki actuator istekleri kimliksiz; ana porttaki /actuator/** ise kapalıdır.
                if (managementPort > 0 && managementPort != serverPort) {
                    auth.requestMatchers(request -> request.getLocalPort() == managementPort
                            && request.getRequestURI().startsWith("/actuator/")).permitAll();
                }
                if (swaggerEnabled) {
                    auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v1/api-docs/**").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> response.sendError(401, "Unauthorized"))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // JWT filtresinden sonra: kullanıcı kimliği biliniyorsa kullanıcı bazlı sınırlar uygulanır.
            .addFilterAfter(new RateLimitFilter(authPerMinute, scanPerMinute, metrics), JwtAuthenticationFilter.class);

        return http.build();
    }
}
