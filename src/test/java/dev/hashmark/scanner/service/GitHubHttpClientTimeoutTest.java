package dev.hashmark.scanner.service;

import com.sun.net.httpserver.HttpServer;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.config.ScanProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubHttpClientTimeoutTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", ex -> {
            hits.incrementAndGet();
            try {
                Thread.sleep(2_000); // yanıt başlıkları asla zamanında gelmez
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void defaultRequestTimeoutBoundsSlowUpstream() {
        ScanProperties props = new ScanProperties();
        props.setHttpResponseTimeoutMs(200);
        GitHubHttpClient client = new GitHubHttpClient(props);
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/slow");

        long start = System.nanoTime();
        ApiException ex = assertThrows(ApiException.class, () -> client.send(client.request(uri, "tok").GET().build()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 4 deneme x 200 ms + geri çekilmeler (1+2+4 sn): 2 sn'lik yanıtı beklemeden, sınırlı sürede pes eder.
        assertEquals(4, hits.get());
        assertTrue(elapsedMs < 12_000, "süre sınırlı olmalıydı: " + elapsedMs + " ms");
        assertTrue(ex.getMessage().contains("Could not reach GitHub"));
    }

    @Test
    void defaultTimeoutsMatchPlan() {
        ScanProperties props = new ScanProperties();
        assertEquals(3_000, props.getHttpConnectTimeoutMs());
        assertEquals(10_000, props.getHttpResponseTimeoutMs());
    }
}
