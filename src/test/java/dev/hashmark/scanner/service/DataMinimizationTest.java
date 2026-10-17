package dev.hashmark.scanner.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.service.GitHubFileService.ScanResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Veri minimizasyonu (plan 5.3, K1/K2): kod diske yazılmaz, yalnızca yorum satırı saklanır, dosya/yorum
 * içeriği hiçbir loga düşmez. Tarama gerçek tar.gz + gerçek ayrıştırıcıyla, sahte GitHub'a karşı yapılır.
 */
class DataMinimizationTest {

    private static final String CODE_SECRET = "SENTINEL_PRIVATE_CODE_" + "a1b2c3";
    private static final String COMMENT_SECRET = "SENTINEL_COMMENT_" + "d4e5f6";

    private HttpServer server;
    private GitHubFileService service;
    private volatile byte[] archive;
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/repos/o/r/contents/.hashmarkignore", ex -> {
            ex.sendResponseHeaders(404, -1);
            ex.close();
        });
        server.createContext("/repos/o/r/tarball", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/codeload/o-r.tgz");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/codeload/o-r.tgz", ex -> {
            ex.sendResponseHeaders(200, archive.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(archive);
            }
            ex.close();
        });
        server.start();
        ScanProperties props = new ScanProperties();
        props.setGithubApiUrl("http://127.0.0.1:" + port);
        service = new GitHubFileService(props, new GitHubHttpClient(props));

        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        server.stop(0);
    }

    private String allLogs() {
        return appender.list.stream()
                .map(e -> e.getFormattedMessage()
                        + (e.getThrowableProxy() != null ? ThrowableProxyUtil.asString(e.getThrowableProxy()) : "")
                        + e.getMDCPropertyMap())
                .collect(Collectors.joining("\n"));
    }

    private static String javaFile() {
        return """
                package x;
                class A {
                    String key = "%s";   // kod satırı: saklanmamalı
                    // TODO: %s refactor
                    void run() { System.out.println(key); }
                }
                """.formatted(CODE_SECRET, COMMENT_SECRET);
    }

    @Test
    void onlyCommentLineIsExtractedAndNothingIsLogged() {
        archive = new TarTestSupport().globalHeader().file("o-r-abc/src/A.java", javaFile()).tarGz();
        DebtParserService parser = new DebtParserService();
        List<DebtDto> debts = new ArrayList<>();

        ScanResult result = service.scanRepository("o/r", "t", null,
                (path, content) -> debts.addAll(parser.parse(content, path)), null);

        assertEquals(1, result.scanned());
        // Satır sonu yorumu da (NOTE) alınabilir; ama yalnızca yorum kısmı, kodun kendisi değil.
        assertTrue(debts.stream().anyMatch(d -> "TODO".equals(d.getLabel()) && d.getContent().contains(COMMENT_SECRET)));
        for (DebtDto d : debts) {
            assertEquals("src/A.java", d.getFilePath());
            // Saklanacak alanların hiçbirinde kod yok (yalnızca yol + yorum satırı, K2)
            assertFalse(d.toString().contains(CODE_SECRET), "kod saklanacak veriye girdi: " + d);
            assertFalse(d.toString().contains("System.out"), "kod saklanacak veriye girdi: " + d);
        }

        String logs = allLogs();
        assertFalse(logs.contains(CODE_SECRET), "kod loglarda");
        assertFalse(logs.contains(COMMENT_SECRET), "yorum loglarda");
    }

    @Test
    void handlerFailureDoesNotLogExceptionMessageWithContent() {
        archive = new TarTestSupport().file("o-r-abc/src/A.java", javaFile()).tarGz();

        ScanResult result = service.scanRepository("o/r", "t", null, (path, content) -> {
            // JDK istisnaları girdiden parça taşır (ör. Integer.parseInt hatası)
            throw new NumberFormatException("For input string: \"" + content + "\"");
        }, null);

        assertEquals(1, result.failed());
        String logs = allLogs();
        assertTrue(logs.contains("NumberFormatException"), "hata türü yine de loglanmalı");
        assertFalse(logs.contains(CODE_SECRET), "kod istisna mesajıyla loglara sızdı");
        assertFalse(logs.contains(COMMENT_SECRET), "yorum istisna mesajıyla loglara sızdı");
    }

    /**
     * Statik denetim: üretim kodunda dosyaya yazan API yok. Tarball bellekte akıtılır; diske geçici kopya
     * düşmez (K1). Gerçekten dosya yazmak gerekirse bu test bilinçli olarak güncellenir ve plan 5.3 değişir.
     */
    @Test
    void productionCodeHasNoDiskWriteApis() throws IOException {
        Pattern forbidden = Pattern.compile(
                "Files\\.(write|writeString|newOutputStream|newBufferedWriter|createTempFile|createTempDirectory|copy|move)\\s*\\("
                + "|new\\s+(FileOutputStream|FileWriter|RandomAccessFile|PrintWriter\\s*\\(\\s*new\\s+File)\\b"
                + "|File\\.createTempFile|\\.transferTo\\s*\\(\\s*(Path|new\\s+File)");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (forbidden.matcher(lines.get(i)).find()) {
                        hits.add(p + ":" + (i + 1) + "  " + lines.get(i).strip());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "Üretim kodunda disk yazımı:\n" + String.join("\n", hits));
    }
}
