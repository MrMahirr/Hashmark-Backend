package dev.hashmark.common.resilience;

import com.sun.net.httpserver.HttpServer;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.resilience.GitHubResilience.Lane;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.service.GitHubHttpClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubResilienceTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 500;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() throws IOException {
        registry = new SimpleMeterRegistry();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private GitHubResilienceProperties smallWindow() {
        GitHubResilienceProperties p = new GitHubResilienceProperties();
        p.setMinimumNumberOfCalls(4);
        p.setSlidingWindowSize(4);
        p.setOpenStateWaitMs(60_000);
        return p;
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void circuitOpensOnServerErrorsAndThenFailsFastWithoutCallingGitHub() {
        GitHubResilience resilience = new GitHubResilience(smallWindow(), registry);
        GitHubHttpClient client = new GitHubHttpClient(new ScanProperties(), null, resilience);
        // POST (idempotent olmayan) = tek deneme: her çağrı tek isabet, bekleme yok
        HttpRequest request = client.request(uri("/x"), "t").POST(HttpRequest.BodyPublishers.noBody()).build();

        for (int i = 0; i < 4; i++) {
            assertEquals(500, client.send(request).statusCode());
        }
        assertEquals(CircuitBreaker.State.OPEN, resilience.state());
        int before = hits.get();

        ApiException ex = assertThrows(ApiException.class, () -> client.send(request));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getHttpStatus());
        assertEquals(GitHubResilience.CODE_UNAVAILABLE, ex.getCode());
        assertEquals(60L, ex.getRetryAfterSeconds());
        assertEquals(before, hits.get(), "açık devre GitHub'a istek göndermemeli");
        assertEquals(1.0, registry.counter("hashmark.github.rejected", "lane", "scan", "reason", "circuit_open").count());
    }

    @Test
    void clientErrorsDoNotOpenTheCircuit() {
        status = 404;
        GitHubResilience resilience = new GitHubResilience(smallWindow(), registry);
        GitHubHttpClient client = new GitHubHttpClient(new ScanProperties(), null, resilience);
        HttpRequest request = client.request(uri("/missing"), "t").GET().build();

        for (int i = 0; i < 10; i++) {
            assertEquals(404, client.send(request).statusCode());
        }
        assertEquals(CircuitBreaker.State.CLOSED, resilience.state());
    }

    @Test
    void fullScanLaneRejectsButInteractiveLaneStillWorks() {
        GitHubResilienceProperties p = new GitHubResilienceProperties();
        p.setScanMaxConcurrent(1);
        p.setScanMaxWaitMs(0);
        GitHubResilience resilience = new GitHubResilience(p, registry);

        try (GitHubResilience.Permit held = resilience.acquire(Lane.SCAN)) {
            ApiException ex = assertThrows(ApiException.class, () -> resilience.acquire(Lane.SCAN));
            assertEquals(GitHubResilience.CODE_BUSY, ex.getCode());
            assertNotNull(ex.getRetryAfterSeconds());
            try (GitHubResilience.Permit interactive = resilience.acquire(Lane.INTERACTIVE)) {
                interactive.success();
            }
            held.success();
        }
        // izin bırakıldı: şerit yeniden kullanılabilir
        try (GitHubResilience.Permit again = resilience.acquire(Lane.SCAN)) {
            again.success();
        }
    }

    @Test
    void unrecordedPermitIsReleasedSoHalfOpenSlotsAreNotLeaked() {
        GitHubResilienceProperties p = smallWindow();
        p.setOpenStateWaitMs(1);
        p.setPermittedCallsInHalfOpen(1);
        GitHubResilience resilience = new GitHubResilience(p, registry);
        resilience.breaker().transitionToOpenState();
        resilience.breaker().transitionToHalfOpenState();

        try (GitHubResilience.Permit ignored = resilience.acquire(Lane.SCAN)) {
            // sonuç bildirilmeden kapatıldı (ör. istemci tarafında beklenmeyen hata)
        }
        try (GitHubResilience.Permit p2 = resilience.acquire(Lane.SCAN)) {
            p2.success();
        }
    }

    @Test
    void interactiveCallMapsNetworkErrorsTo502AndCountsThem() {
        GitHubResilience resilience = new GitHubResilience(smallWindow(), registry);
        for (int i = 0; i < 4; i++) {
            ApiException ex = assertThrows(ApiException.class, () -> resilience.callInteractive(() -> {
                throw new ResourceAccessException("timeout");
            }));
            assertEquals(HttpStatus.BAD_GATEWAY, ex.getHttpStatus());
        }
        assertEquals(CircuitBreaker.State.OPEN, resilience.state());
    }

    @Test
    void interactiveCallPassesClientErrorsThroughAsSuccessForTheBreaker() {
        GitHubResilience resilience = new GitHubResilience(smallWindow(), registry);
        for (int i = 0; i < 6; i++) {
            assertThrows(HttpClientErrorException.class, () -> resilience.callInteractive(() -> {
                throw HttpClientErrorException.create(HttpStatus.UNAUTHORIZED, "bad", null, null, null);
            }));
        }
        assertEquals(CircuitBreaker.State.CLOSED, resilience.state());
        assertEquals("ok", resilience.callInteractive(() -> "ok"));
    }

    @Test
    void metricsAreExported() {
        new GitHubResilience(new GitHubResilienceProperties(), registry);
        assertNotNull(registry.find("resilience4j.circuitbreaker.state").tag("name", "github").gauges());
        assertTrue(registry.find("resilience4j.bulkhead.available.concurrent.calls").gauges().size() >= 2);
    }
}
