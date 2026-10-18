package dev.hashmark.pr;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.service.GitHubHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHub REST uçlarını yerel HttpServer ile taklit eder. */
class GitHubPullRequestClientTest {

    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String HEAD = "2222222222222222222222222222222222222222";

    private HttpServer server;
    private GitHubPullRequestClient client;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile String comments = "[]";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/o/r/", ex -> {
            String uri = ex.getRequestURI().getRawPath()
                    + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            calls.add(ex.getRequestMethod() + " " + uri);
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (uri.startsWith("/repos/o/r/compare/")) {
                respond(ex, 200, "{\"files\":[{\"filename\":\"a.ts\",\"status\":\"modified\",\"patch\":\"@@ -1 +1 @@\\n+x\"},"
                        + "{\"filename\":\"bin.png\",\"status\":\"added\"}]}");
            } else if (uri.startsWith("/repos/o/r/contents/src/my%20file.ts")) {
                respond(ex, 200, "// TODO: x\n");
            } else if (uri.startsWith("/repos/o/r/contents/big.ts")) {
                respond(ex, 200, "x".repeat(100));
            } else if (uri.startsWith("/repos/o/r/contents/")) {
                respond(ex, 404, "{}");
            } else if (uri.equals("/repos/o/r/pulls/7")) {
                respond(ex, 200, "{\"head\":{\"sha\":\"" + HEAD + "\"}}");
            } else if (uri.startsWith("/repos/o/r/issues/7/comments") && "GET".equals(ex.getRequestMethod())) {
                respond(ex, 200, comments);
            } else if (uri.startsWith("/repos/o/r/issues/7/comments")) {
                respond(ex, 201, "{\"id\":1}");
            } else if (uri.startsWith("/repos/o/r/issues/comments/")) {
                respond(ex, 200, "{}");
            } else if (uri.startsWith("/repos/o/r/statuses/")) {
                respond(ex, 201, "{}");
            } else {
                respond(ex, 404, "{}");
            }
        });
        server.start();
        ScanProperties props = new ScanProperties();
        props.setGithubApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new GitHubPullRequestClient(new GitHubHttpClient(props));
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
    void compareUsesThreeDotRangeAndKeepsMissingPatchAsNull() {
        GitHubPullRequestClient.Comparison diff = client.compare("o/r", "t", BASE, HEAD);

        assertEquals("GET /repos/o/r/compare/" + BASE + "..." + HEAD, calls.get(0));
        assertEquals(2, diff.files().size());
        assertEquals("@@ -1 +1 @@\n+x", diff.files().get(0).patch());
        assertNull(diff.files().get(1).patch());
        assertFalse(diff.truncated());
    }

    @Test
    void fileAtEncodesPathAndRespectsLimits() {
        assertEquals(Optional.of("// TODO: x\n"), client.fileAt("o/r", "t", "src/my file.ts", HEAD, 1000));
        assertTrue(calls.get(0).endsWith("/contents/src/my%20file.ts?ref=" + HEAD), calls.get(0));
        assertTrue(client.fileAt("o/r", "t", "big.ts", HEAD, 10).isEmpty(), "boyut sınırı");
        assertTrue(client.fileAt("o/r", "t", "missing.ts", HEAD, 1000).isEmpty());
        assertThrows(ApiException.class, () -> client.fileAt("o/r", "t", "../secret", HEAD, 1000));
        assertThrows(ApiException.class, () -> client.fileAt("o/r", "t", "a.ts", "main", 1000), "yalnızca sha");
    }

    @Test
    void upsertUpdatesMarkedCommentOrCreatesOnlyWhenAsked() {
        comments = "[{\"id\":3,\"body\":\"hi\"},{\"id\":4,\"body\":\"<!-- hashmark:debt-report --> old\"}]";
        assertTrue(client.upsertComment("o/r", "t", 7, PrDebtReport.MARKER, "new", false));
        assertEquals("PATCH /repos/o/r/issues/comments/4", calls.get(1));
        assertTrue(bodies.get(1).contains("\"body\":\"new\""));

        calls.clear();
        comments = "[]";
        assertFalse(client.upsertComment("o/r", "t", 7, PrDebtReport.MARKER, "clean", false));
        assertEquals(1, calls.size(), "yorum yokken temiz sonuç için yeni yorum açılmaz");
        assertTrue(client.upsertComment("o/r", "t", 7, PrDebtReport.MARKER, "debts", true));
        assertEquals("POST /repos/o/r/issues/7/comments", calls.get(calls.size() - 1));
    }

    @Test
    void statusAndCurrentHead() {
        client.setStatus("o/r", "t", HEAD, "failure", "d".repeat(200), "https://hm/x", PrDebtReport.STATUS_CONTEXT);
        assertEquals("POST /repos/o/r/statuses/" + HEAD, calls.get(0));
        assertTrue(bodies.get(0).contains("\"context\":\"hashmark/debt-budget\""));
        assertTrue(bodies.get(0).contains("\"state\":\"failure\""));
        assertFalse(bodies.get(0).contains("d".repeat(141)), "açıklama 140 karakterle sınırlı");

        assertEquals(Optional.of(HEAD), client.currentHead("o/r", "t", 7));
        assertTrue(client.currentHead("o/r", "t", 8).isEmpty());
    }
}
