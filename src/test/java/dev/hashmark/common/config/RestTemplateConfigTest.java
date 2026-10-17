package dev.hashmark.common.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestTemplateConfigTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(3_000); // GitHub'ı simüle eden yavaş sahte sunucu
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
    void slowUpstreamFailsWithinReadTimeoutInsteadOfBlocking() {
        RestTemplate rest = new RestTemplateConfig().restTemplate(new RestTemplateBuilder(), 500, 500);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow";

        long start = System.nanoTime();
        assertThrows(ResourceAccessException.class, () -> rest.getForEntity(url, String.class));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 2_000, "istek zaman aşımına uğramalıydı, süre: " + elapsedMs + " ms");
    }
}
