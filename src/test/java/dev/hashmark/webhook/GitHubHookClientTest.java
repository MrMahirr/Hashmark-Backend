package dev.hashmark.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.service.GitHubHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHub hooks API'sini yerel HttpServer ile taklit eder. */
class GitHubHookClientTest {

    private static final String URL = "https://hm.example/api/v1/webhooks/github/5";

    private HttpServer server;
    private GitHubHookClient client;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int createStatus = 201;
    private volatile int patchStatus = 200;
    private volatile int deleteStatus = 204;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/hooks", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            calls.add(method + " " + path);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if ("POST".equals(method)) {
                respond(ex, createStatus, createStatus == 201 ? "{\"id\":101}" : "{\"message\":\"Validation Failed\"}");
            } else if ("GET".equals(method)) {
                respond(ex, 200, "[{\"id\":7,\"config\":{\"url\":\"https://other\"}},{\"id\":9,\"config\":{\"url\":\"" + URL + "\"}}]");
            } else if ("PATCH".equals(method)) {
                respond(ex, patchStatus, "{}");
            } else {
                respond(ex, deleteStatus, "");
            }
        });
        server.start();

        ScanProperties props = new ScanProperties();
        props.setGithubApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new GitHubHookClient(new GitHubHttpClient(props));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    @Test
    void createsPushOnlyJsonHook() {
        assertEquals(101, client.createOrAdopt("o/r", "t", URL, "s3cret"));

        assertEquals(List.of("POST /repos/o/r/hooks"), calls);
        String body = bodies.get(0);
        assertTrue(body.contains("\"events\":[\"push\"]"), body);
        assertTrue(body.contains("\"content_type\":\"json\""), body);
        assertTrue(body.contains("\"insecure_ssl\":\"0\""), body);
        assertTrue(body.contains("\"secret\":\"s3cret\""), body);
    }

    @Test
    void adoptsExistingHookWithSameUrlOn422() {
        createStatus = 422;

        assertEquals(9, client.createOrAdopt("o/r", "t", URL, "new"));
        assertEquals(List.of("POST /repos/o/r/hooks", "GET /repos/o/r/hooks", "PATCH /repos/o/r/hooks/9"), calls);
        assertTrue(bodies.get(2).contains("\"secret\":\"new\""));
    }

    @Test
    void missingAdminRightsBecomeForbidden() {
        createStatus = 404;

        ApiException e = assertThrows(ApiException.class, () -> client.createOrAdopt("o/r", "t", URL, "s"));
        assertEquals(HttpStatus.FORBIDDEN, e.getHttpStatus());
        assertTrue(e.getMessage().contains("Admin access"));
    }

    @Test
    void updateAndDeleteTreatMissingHookGracefully() {
        assertTrue(client.updateSecret("o/r", "t", 9, URL, "s"));
        patchStatus = 404;
        assertFalse(client.updateSecret("o/r", "t", 9, URL, "s"));

        assertTrue(client.delete("o/r", "t", 9));
        deleteStatus = 404;
        assertTrue(client.delete("o/r", "t", 9), "zaten silinmiş hook başarı sayılır");
        deleteStatus = 403;
        assertFalse(client.delete("o/r", "t", 9));
    }
}
