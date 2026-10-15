package dev.hashmark.scanner.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.service.GitHubFileService.ScanResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHub API'sini yerel bir HttpServer ile taklit ederek uçtan uca tarama davranışını doğrular. */
class GitHubFileServiceTest {

    private HttpServer server;
    private ScanProperties props;
    private GitHubFileService service;

    private final AtomicInteger tarballCalls = new AtomicInteger();
    private final List<String> codeloadAuthHeaders = new CopyOnWriteArrayList<>();
    private volatile byte[] archive;
    private volatile String ignoreFile;          // null → 404
    private volatile int tarballFailuresBeforeSuccess;
    private volatile int tarballFailureStatus = 429;
    private volatile int tarballFinalStatus = 302;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();

        server.createContext("/repos/o/r/contents/.hashmarkignore", ex -> {
            if (ignoreFile == null) {
                respond(ex, 404, new byte[0], Map.of());
            } else {
                respond(ex, 200, ignoreFile.getBytes(StandardCharsets.UTF_8), Map.of());
            }
        });
        server.createContext("/repos/o/r/tarball", ex -> {
            int call = tarballCalls.incrementAndGet();
            if (call <= tarballFailuresBeforeSuccess) {
                respond(ex, tarballFailureStatus, new byte[0], Map.of("Retry-After", "1"));
                return;
            }
            if (tarballFinalStatus == 302) {
                respond(ex, 302, new byte[0], Map.of("Location", "http://127.0.0.1:" + port + "/codeload/o-r.tgz"));
            } else {
                respond(ex, tarballFinalStatus, new byte[0], Map.of());
            }
        });
        server.createContext("/codeload/o-r.tgz", ex -> {
            codeloadAuthHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            respond(ex, 200, archive, Map.of());
        });
        server.start();

        props = new ScanProperties();
        props.setGithubApiUrl("http://127.0.0.1:" + port);
        props.setMaxRateLimitWaitSeconds(5);
        service = new GitHubFileService(props);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static void respond(HttpExchange ex, int status, byte[] body, Map<String, String> headers) throws IOException {
        headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
        ex.close();
    }

    private ScanResult scan(Map<String, String> sink) {
        return service.scanRepository("o/r", "secret-token", sink::put, null);
    }

    @Test
    void scansArchiveWithSingleDownloadAndFiltersFiles() {
        archive = new TarTestSupport()
                .globalHeader()
                .directory("o-r-abc/")
                .file("o-r-abc/src/Main.java", "// TODO: a")
                .file("o-r-abc/node_modules/lib/x.js", "// TODO: excluded dir")
                .file("o-r-abc/dist/app.js", "// TODO: excluded dir")
                .file("o-r-abc/web/app.min.js", "// TODO: minified")
                .file("o-r-abc/README.md", "TODO: unsupported ext")
                .file("o-r-abc/lib/blob.py", new byte[]{'a', 0, 'b'})
                .file("o-r-abc/scripts/run.sh", "# FIXME: b")
                .tarGz();

        Map<String, String> seen = new LinkedHashMap<>();
        ScanResult result = scan(seen);

        assertEquals(Map.of("src/Main.java", "// TODO: a", "scripts/run.sh", "# FIXME: b"), seen);
        assertEquals(2, result.scanned());
        assertEquals(1, result.skipped(), "ikili dosya atlanmalı");
        assertEquals(0, result.failed());
        assertFalse(result.truncated());
        assertEquals(1, tarballCalls.get(), "tek tarball isteği");
        assertEquals(List.of("null"), codeloadAuthHeaders, "token codeload'a gönderilmemeli");
    }

    @Test
    void honoursHashmarkIgnoreFile() {
        ignoreFile = "generated/\n*.skip.py\n";
        archive = new TarTestSupport()
                .file("o-r-abc/generated/a.py", "# TODO: no")
                .file("o-r-abc/b.skip.py", "# TODO: no")
                .file("o-r-abc/c.py", "# TODO: yes")
                .tarGz();

        Map<String, String> seen = new LinkedHashMap<>();
        scan(seen);

        assertEquals(List.of("c.py"), List.copyOf(seen.keySet()));
    }

    @Test
    void oversizedFilesAreSkippedAndKeptOpen() {
        props.setMaxFileSizeBytes(100);
        archive = new TarTestSupport()
                .file("o-r-abc/big.java", "x".repeat(500))
                .file("o-r-abc/small.java", "// TODO: s")
                .tarGz();

        Map<String, String> seen = new LinkedHashMap<>();
        ScanResult result = scan(seen);

        assertEquals(List.of("small.java"), List.copyOf(seen.keySet()));
        assertEquals(1, result.skipped());
        assertTrue(result.keepOpenPaths().contains("big.java"));
    }

    @Test
    void stopsAtFileLimitAndReportsTruncation() {
        props.setMaxFiles(2);
        TarTestSupport tar = new TarTestSupport();
        for (int i = 0; i < 5; i++) {
            tar.file("o-r-abc/F" + i + ".java", "// TODO: " + i);
        }
        archive = tar.tarGz();

        Map<String, String> seen = new LinkedHashMap<>();
        ScanResult result = scan(seen);

        assertEquals(2, result.scanned());
        assertTrue(result.truncated());
        assertTrue(result.truncationReason().contains("File limit"));
        assertEquals(result.scannedPaths(), java.util.Set.copyOf(seen.keySet()));
    }

    @Test
    void handlerFailuresAreCountedAndFileKeptOpen() {
        archive = new TarTestSupport()
                .file("o-r-abc/bad.java", "// TODO: boom")
                .file("o-r-abc/good.java", "// TODO: ok")
                .tarGz();

        List<String> ok = new CopyOnWriteArrayList<>();
        ScanResult result = service.scanRepository("o/r", "t", (path, content) -> {
            if (path.equals("bad.java")) {
                throw new IllegalStateException("parser exploded");
            }
            ok.add(path);
        }, null);

        assertEquals(List.of("good.java"), ok);
        assertEquals(1, result.scanned());
        assertEquals(1, result.failed());
        assertTrue(result.keepOpenPaths().contains("bad.java"));
    }

    @Test
    void retriesAfterRateLimitWithRetryAfter() {
        tarballFailuresBeforeSuccess = 1; // ilk istek 429 + Retry-After: 1
        archive = new TarTestSupport().file("o-r-abc/A.java", "// TODO: a").tarGz();

        Map<String, String> seen = new LinkedHashMap<>();
        ScanResult result = scan(seen);

        assertEquals(1, result.scanned());
        assertEquals(2, tarballCalls.get());
    }

    @Test
    void retriesServerErrors() {
        tarballFailuresBeforeSuccess = 1;
        tarballFailureStatus = 503;
        archive = new TarTestSupport().file("o-r-abc/A.java", "// TODO: a").tarGz();

        ScanResult result = scan(new LinkedHashMap<>());

        assertEquals(1, result.scanned());
        assertEquals(2, tarballCalls.get());
    }

    @Test
    void failsClearlyWhenRateLimitWaitIsTooLong() {
        props.setMaxRateLimitWaitSeconds(0); // 1 sn beklemeyi bile reddet
        tarballFailuresBeforeSuccess = 10;
        archive = new TarTestSupport().tarGz();

        ApiException ex = assertThrows(ApiException.class, () -> scan(new LinkedHashMap<>()));

        assertEquals(429, ex.getHttpStatus().value());
        assertTrue(ex.getMessage().contains("rate limit"));
        assertEquals(1, tarballCalls.get(), "beklemeden vazgeçmeli");
    }

    @Test
    void mapsNotFoundAndEmptyRepository() {
        tarballFinalStatus = 404;
        ApiException notFound = assertThrows(ApiException.class, () -> scan(new LinkedHashMap<>()));
        assertEquals(404, notFound.getHttpStatus().value());

        tarballFinalStatus = 409; // boş depo
        ScanResult empty = scan(new LinkedHashMap<>());
        assertEquals(0, empty.scanned());
        assertFalse(empty.truncated());
    }

    @Test
    void rejectsSuspiciousRepositoryNames() {
        assertThrows(ApiException.class, () -> service.scanRepository("../etc/passwd", "t", (p, c) -> { }, null));
        assertThrows(ApiException.class, () -> service.scanRepository("o/r?x=1", "t", (p, c) -> { }, null));
        assertThrows(ApiException.class, () -> service.scanRepository(null, "t", (p, c) -> { }, null));
    }

    @Test
    void reportsProgressInBatches() {
        TarTestSupport tar = new TarTestSupport();
        for (int i = 0; i < 120; i++) {
            tar.file("o-r-abc/F" + i + ".java", "// TODO: " + i);
        }
        archive = tar.tarGz();

        List<Integer> progress = new CopyOnWriteArrayList<>();
        service.scanRepository("o/r", "t", (p, c) -> { }, progress::add);

        assertEquals(List.of(50, 100), progress);
    }
}
