package dev.hashmark.scanner.service;

import com.sun.net.httpserver.HttpServer;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.resilience.GitHubResilience.Lane;
import dev.hashmark.scanner.config.ScanProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubHttpClientRetryTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 503;
    private volatile String retryAfter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            if (retryAfter != null) {
                ex.getResponseHeaders().add("Retry-After", retryAfter);
            }
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void nonIdempotentRequestIsNotRetried() {
        GitHubHttpClient client = new GitHubHttpClient(new ScanProperties());
        HttpRequest post = client.request(uri("/repos/o/r/issues"), "t")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();

        assertEquals(503, client.send(post).statusCode());
        assertEquals(1, hits.get());
    }

    @Test
    void interactiveLaneRetriesOnceThenGivesUp() {
        GitHubHttpClient client = new GitHubHttpClient(new ScanProperties());
        HttpRequest get = client.request(uri("/repos/o/r/contents/a"), "t").GET().build();

        long start = System.nanoTime();
        assertEquals(503, client.send(get, Lane.INTERACTIVE).statusCode());
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertEquals(2, hits.get(), "kullanıcı yolu: en fazla 2 deneme");
        assertTrue(ms >= 400 && ms < 2_500, "jitter'lı ~0,5–1,5 sn bekleme: " + ms);
    }

    @Test
    void interactiveLaneDoesNotWaitForRateLimitAndReportsRetryAfter() {
        status = 429;
        retryAfter = "30";
        GitHubHttpClient client = new GitHubHttpClient(new ScanProperties());
        HttpRequest get = client.request(uri("/user"), "t").GET().build();

        ApiException ex = assertThrows(ApiException.class, () -> client.send(get, Lane.INTERACTIVE));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getHttpStatus());
        assertEquals("GITHUB_RATE_LIMITED", ex.getCode());
        assertEquals(30L, ex.getRetryAfterSeconds());
        assertEquals(1, hits.get(), "kullanıcı isteği rate-limit için bekletilmez");
        assertTrue(ex.isTransient(), "tarama işçisi bunu erteleme sebebi sayar");
    }

    @Test
    void idempotencyRules() {
        GitHubHttpClient client = new GitHubHttpClient(new ScanProperties());
        assertTrue(GitHubHttpClient.isIdempotent(client.request(uri("/x"), "t").GET().build()));
        assertTrue(GitHubHttpClient.isIdempotent(client.request(uri("/graphql"), "t")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build()), "blame sorgusu salt okuma");
        assertFalse(GitHubHttpClient.isIdempotent(client.request(uri("/x"), "t")
                .POST(HttpRequest.BodyPublishers.noBody()).build()));
        assertFalse(GitHubHttpClient.isIdempotent(client.request(uri("/x"), "t").DELETE().build()));
    }

    @Test
    void backoffIsJitteredAroundExponentialBase() {
        Set<Long> distinct = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            long first = GitHubHttpClient.backoffMillis(1);
            long third = GitHubHttpClient.backoffMillis(3);
            assertTrue(first >= 500 && first <= 1_500, "1. deneme: " + first);
            assertTrue(third >= 2_000 && third <= 6_000, "3. deneme: " + third);
            distinct.add(first);
        }
        assertTrue(distinct.size() > 20, "bekleme süreleri dağılmalı (thundering herd yok)");
    }
}
