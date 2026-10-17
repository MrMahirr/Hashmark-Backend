package dev.hashmark.common.config;

import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.common.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
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

    @Value("${load-shedding.enabled:true}")
    private boolean loadSheddingEnabled;

    @Value("${load-shedding.db-pending-threshold:1}")
    private int loadSheddingDbPending;

    @Value("${load-shedding.max-inflight:100}")
    private int loadSheddingMaxInflight;

    private final AppMetrics metrics;
    private final dev.hashmark.consent.ConsentService consentService;
    private final RateLimitProperties rateLimitProperties;
    private final MeterRegistry meterRegistry;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, AppMetrics metrics,
                          dev.hashmark.consent.ConsentService consentService, RateLimitProperties rateLimitProperties,
                          MeterRegistry meterRegistry) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.metrics = metrics;
        this.consentService = consentService;
        this.rateLimitProperties = rateLimitProperties;
        this.meterRegistry = meterRegistry;
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
                // GitHub teslimatları kimliksizdir; GitHubWebhookService her isteği depo anahtarıyla (HMAC) doğrular
                auth.requestMatchers(org.springframework.http.HttpMethod.POST, "/webhooks/github/*").permitAll();
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
            .addFilterAfter(new RateLimitFilter(rateLimitProperties, metrics), JwtAuthenticationFilter.class)
            // Yük atma (Faz 3): hız sınırından sonra; baskı altında yalnızca pahalı uçlar 503 alır
            .addFilterAfter(new LoadSheddingFilter(loadSheddingEnabled, loadSheddingDbPending, loadSheddingMaxInflight,
                    meterRegistry, metrics), RateLimitFilter.class)
            // Onay kapısı (plan 5.10): kimlik bilindikten sonra, controller'lardan önce
            .addFilterAfter(new dev.hashmark.consent.ConsentGateFilter(consentService), LoadSheddingFilter.class);

        return http.build();
    }
}
