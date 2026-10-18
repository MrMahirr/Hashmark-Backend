package dev.hashmark.issue;

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

class GitHubIssueClientTest {

    private HttpServer server;
    private GitHubIssueClient client;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile boolean rejectLabels;
    private volatile int status = 201;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/issues", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            int code = rejectLabels && body.contains("\"labels\"") ? 422 : status;
            byte[] out = (code == 201 ? "{\"number\":42,\"html_url\":\"https://github.com/o/r/issues/42\"}" : "{}")
                    .getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        ScanProperties props = new ScanProperties();
        props.setGithubApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new GitHubIssueClient(new GitHubHttpClient(props));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsIssueWithLabels() {
        GitHubIssueClient.CreatedIssue issue = client.create("o/r", "t", "TODO: x", "body", List.of("hashmark", "todo"));

        assertEquals(42, issue.number());
        assertEquals("https://github.com/o/r/issues/42", issue.url());
        assertTrue(bodies.get(0).contains("\"labels\":[\"hashmark\",\"todo\"]"), bodies.get(0));
    }

    @Test
    void retriesWithoutLabelsWhenGithubRejectsThem() {
        rejectLabels = true;

        assertEquals(42, client.create("o/r", "t", "TODO: x", "body", List.of("hashmark")).number());
        assertEquals(2, bodies.size());
        assertFalse(bodies.get(1).contains("labels"));
    }

    @Test
    void mapsErrors() {
        status = 410;
        ApiException disabled = assertThrows(ApiException.class, () -> client.create("o/r", "t", "a", "b", List.of()));
        assertTrue(disabled.getMessage().contains("disabled"));

        status = 404;
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> client.create("o/r", "t", "a", "b", List.of())).getHttpStatus());
    }
}
