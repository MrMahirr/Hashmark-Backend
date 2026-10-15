package dev.hashmark.scanner.service;

import com.sun.net.httpserver.HttpServer;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.dto.BlameRange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubBlameServiceTest {

    private HttpServer server;
    private GitHubBlameService service;
    private volatile String responseBody;
    private volatile int responseStatus = 200;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/graphql", ex -> {
            requestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authHeader.set(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(responseStatus, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();

        ScanProperties props = new ScanProperties();
        props.setGithubApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        service = new GitHubBlameService(new GitHubHttpClient(props));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void parsesRangesAndSendsQuery() {
        responseBody = """
                {"data":{"repository":{"object":{"blame":{"ranges":[
                  {"startingLine":1,"endingLine":10,"commit":{"oid":"aaa","authoredDate":"2025-03-04T05:06:07Z",
                    "author":{"name":"Mahir R","user":{"login":"mahir"}}}},
                  {"startingLine":11,"endingLine":20,"commit":{"oid":"bbb","authoredDate":"2024-01-01T00:00:00Z",
                    "author":{"name":"No Account","user":null}}}
                ]}}}}}
                """;

        Optional<List<BlameRange>> ranges = service.fetchBlame("o/r", "tok", "abc123", "src/A.java");

        assertTrue(ranges.isPresent());
        assertEquals(2, ranges.get().size());
        BlameRange first = ranges.get().get(0);
        assertEquals("mahir", first.authorLogin());
        assertEquals("Mahir R", first.authorName());
        assertEquals("aaa", first.commitSha());
        assertEquals(LocalDateTime.of(2025, 3, 4, 5, 6, 7), first.authoredAt());
        assertTrue(first.contains(10));
        assertEquals(false, first.contains(11));
        assertEquals(null, ranges.get().get(1).authorLogin());

        assertEquals("Bearer tok", authHeader.get());
        assertTrue(requestBody.get().contains("\"oid\":\"abc123\""));
        assertTrue(requestBody.get().contains("\"path\":\"src/A.java\""));
        assertTrue(requestBody.get().contains("\"owner\":\"o\""));
    }

    @Test
    void returnsEmptyOnGraphqlErrors() {
        responseBody = "{\"errors\":[{\"message\":\"boom\"}],\"data\":null}";

        assertTrue(service.fetchBlame("o/r", "t", "abc", "a.java").isEmpty());
    }

    @Test
    void returnsEmptyWhenObjectIsNotACommit() {
        responseBody = "{\"data\":{\"repository\":{\"object\":null}}}";

        assertTrue(service.fetchBlame("o/r", "t", "abc", "a.java").isEmpty());
    }

    @Test
    void returnsEmptyOnHttpError() {
        responseStatus = 401;
        responseBody = "{}";

        assertTrue(service.fetchBlame("o/r", "t", "abc", "a.java").isEmpty());
    }
}
