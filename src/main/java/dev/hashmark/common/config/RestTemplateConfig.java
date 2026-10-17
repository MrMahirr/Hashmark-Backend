package dev.hashmark.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Dış servis çağrıları (GitHub OAuth, repo listesi, Brevo) için zaman aşımlı istemci.
 * Zaman aşımı olmazsa yavaş bir dış servis istek iş parçacıklarını süresiz bloklar ve havuzu tüketir.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder,
                                     @Value("${http.client.connect-timeout-ms:3000}") long connectTimeoutMs,
                                     @Value("${http.client.read-timeout-ms:10000}") long readTimeoutMs) {
        return builder
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .readTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
    }
}
