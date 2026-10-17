package dev.hashmark.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import dev.hashmark.auth.repository.OAuthStateRepository;
import dev.hashmark.auth.repository.RefreshTokenRepository;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.auth.service.AuthService;
import dev.hashmark.auth.service.GitHubOAuthService;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.service.GitHubHttpClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bilinen bir sırrı (GitHub token, refresh token) hassas yollardan geçirir ve tüm log çıktısında aratır.
 * Plan Faz 1: "hassas veri (token, kod) loglanmaz (test)".
 */
class LogHygieneTest {

    private static final String GITHUB_TOKEN = "gho_SUPERSECRETGITHUBTOKEN1234567890";

    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
    }

    private String allLogs() {
        return appender.list.stream()
                .map(e -> e.getFormattedMessage()
                        + (e.getThrowableProxy() != null ? ThrowableProxyUtil.asString(e.getThrowableProxy()) : "")
                        + e.getMDCPropertyMap())
                .collect(Collectors.joining("\n"));
    }

    @Test
    void githubClientFailureDoesNotLogToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(502, -1);
            ex.close();
        });
        server.start();
        try {
            ScanProperties props = new ScanProperties();
            props.setGithubApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
            GitHubHttpClient client = new GitHubHttpClient(props, new AppMetrics(new SimpleMeterRegistry()));
            var uri = java.net.URI.create(props.getGithubApiUrl() + "/x");
            // 5xx: yeniden deneme + geri çekilme akışı çalışır; token loglara sızmamalı.
            try {
                client.send(client.request(uri, GITHUB_TOKEN).GET().build());
            } catch (ApiException ignored) {
                // beklenen
            }
        } finally {
            server.stop(0);
        }
        assertFalse(allLogs().contains(GITHUB_TOKEN), "GitHub token loglarda bulundu");
    }

    @Test
    void refreshReuseDetectionDoesNotLogTokenValue() {
        JwtUtil jwt = new JwtUtil("thisisaverylongsecretkeythatneedstobeatleast256bitslong", 900000, 604800000);
        RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
        AuthService service = new AuthService(mock(GitHubOAuthService.class), mock(UserRepository.class), jwt,
                mock(AesEncryptionUtil.class), refreshTokens, mock(OAuthStateRepository.class),
                new AppMetrics(new SimpleMeterRegistry()));
        ReflectionTestUtils.setField(service, "accessTokenExpiry", 900000L);

        String refresh = jwt.generateRefreshToken(7L, "jti-1", "fam-1");
        when(refreshTokens.markUsedIfActive(anyString())).thenReturn(false);
        when(refreshTokens.findState("jti-1"))
                .thenReturn(Optional.of(new RefreshTokenRepository.TokenState("fam-1", true, false, 120)));

        assertThrows(ApiException.class, () -> service.refresh(refresh));

        String logs = allLogs();
        assertTrue(logs.contains("yeniden kullanımı"), "tespit logu beklenirdi");
        assertFalse(logs.contains(refresh), "refresh token loglarda bulundu");
    }
}
