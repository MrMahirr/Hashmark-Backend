package dev.hashmark.debt.service;

import com.sun.net.httpserver.HttpServer;
import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.debt.dto.DebtContextDto;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.service.GitHubHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DebtContextServiceTest {

    private HttpServer server;
    private DebtContextService service;
    private DebtRepository debts;
    private volatile int status = 200;
    private volatile byte[] body = new byte[0];
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestQuery = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requestPath.set(ex.getRequestURI().getRawPath());
            requestQuery.set(ex.getRequestURI().getRawQuery());
            authorization.set(ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();

        ScanProperties props = new ScanProperties();
        props.setGithubApiUrl("http://127.0.0.1:" + server.getAddress().getPort());

        debts = mock(DebtRepository.class);
        UserRepository users = mock(UserRepository.class);
        AesEncryptionUtil aes = mock(AesEncryptionUtil.class);
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).githubToken("enc").build()));
        when(aes.decrypt("enc")).thenReturn("plain-token");

        service = new DebtContextService(debts, users, aes, new GitHubHttpClient(props));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void givenDebt(int line, String sha) {
        DebtDto dto = DebtDto.builder().id(9L).repoFullName("o/r").filePath("src/My File.java")
                .lineNo(line).commitSha(sha).label("TODO").content("x").build();
        when(debts.findByIdForUser(1L, 9L)).thenReturn(Optional.of(dto));
    }

    private static String fileOf(int lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    void returnsWindowAroundTheDebtLine() {
        givenDebt(20, "abc123");
        body = fileOf(100).getBytes(StandardCharsets.UTF_8);

        DebtContextDto result = service.getContext(1L, 9L, 3);

        assertEquals(17, result.getStartLine());
        assertEquals(20, result.getHighlightLine());
        assertEquals(7, result.getLines().size());
        assertEquals("line 17", result.getLines().get(0));
        assertEquals("line 23", result.getLines().get(6));
        assertEquals("abc123", result.getCommitSha());
        assertFalse(result.isTooLarge());
        assertEquals("/repos/o/r/contents/src/My%20File.java", requestPath.get());
        assertEquals("ref=abc123", requestQuery.get());
        assertEquals("Bearer plain-token", authorization.get());
    }

    @Test
    void clampsWindowAtFileStartAndEnd() {
        givenDebt(2, null);
        body = fileOf(4).getBytes(StandardCharsets.UTF_8);

        DebtContextDto result = service.getContext(1L, 9L, 5);

        assertEquals(1, result.getStartLine());
        assertEquals(5, result.getLines().size()); // 4 satır + sondaki boş satır
        assertEquals(null, requestQuery.get(), "sha yoksa ref gönderilmez");
    }

    @Test
    void clampsRadiusToMaximum() {
        givenDebt(100, "abc");
        body = fileOf(300).getBytes(StandardCharsets.UTF_8);

        DebtContextDto result = service.getContext(1L, 9L, 9999);

        assertEquals(61, result.getLines().size()); // ±30
        assertEquals(70, result.getStartLine());
    }

    @Test
    void defaultsRadiusWhenMissing() {
        givenDebt(50, "abc");
        body = fileOf(100).getBytes(StandardCharsets.UTF_8);

        assertEquals(11, service.getContext(1L, 9L, null).getLines().size());
    }

    @Test
    void reportsTooLargeFilesWithoutContent() {
        givenDebt(5, "abc");
        body = new byte[1_048_576 + 10];
        java.util.Arrays.fill(body, (byte) 'a');

        DebtContextDto result = service.getContext(1L, 9L, 5);

        assertTrue(result.isTooLarge());
        assertTrue(result.getLines().isEmpty());
    }

    @Test
    void mapsMissingFileAndOtherErrors() {
        givenDebt(5, "abc");

        status = 404;
        ApiException notFound = assertThrows(ApiException.class, () -> service.getContext(1L, 9L, 5));
        assertEquals(404, notFound.getHttpStatus().value());

        status = 401; // GitHub token'ı geçersiz → bizim 401'imiz DEĞİL (istemci oturumu düşürmesin)
        ApiException revoked = assertThrows(ApiException.class, () -> service.getContext(1L, 9L, 5));
        assertEquals(502, revoked.getHttpStatus().value());
    }

    @Test
    void unknownDebtIsNotFound() {
        when(debts.findByIdForUser(1L, 77L)).thenReturn(Optional.empty());

        ApiException e = assertThrows(ApiException.class, () -> service.getContext(1L, 77L, 5));

        assertEquals(404, e.getHttpStatus().value());
    }
}
