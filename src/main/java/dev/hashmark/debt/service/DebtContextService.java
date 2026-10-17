package dev.hashmark.debt.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.debt.dto.DebtContextDto;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.scanner.service.GitHubHttpClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bir borcun çevresindeki ±N satırı GitHub'dan (borcun son görüldüğü commit'te) getirir.
 * Kod veritabanında tutulmaz; her istekte kullanıcının kendi token'ıyla okunur.
 */
@Service
public class DebtContextService {

    static final int DEFAULT_RADIUS = 5;
    static final int MAX_RADIUS = 30;
    private static final int MAX_FILE_BYTES = 1_048_576;
    private static final int MAX_LINE_LENGTH = 500;

    private final DebtRepository debtRepository;
    private final UserRepository userRepository;
    private final AesEncryptionUtil aesEncryptionUtil;
    private final GitHubHttpClient http;

    public DebtContextService(DebtRepository debtRepository, UserRepository userRepository,
                              AesEncryptionUtil aesEncryptionUtil, GitHubHttpClient http) {
        this.debtRepository = debtRepository;
        this.userRepository = userRepository;
        this.aesEncryptionUtil = aesEncryptionUtil;
        this.http = http;
    }

    public DebtContextDto getContext(Long userId, Long debtId, Integer requestedRadius) {
        DebtDto debt = debtRepository.findByIdForUser(userId, debtId)
                .orElseThrow(() -> ApiException.notFound("Debt not found"));

        int radius = requestedRadius == null ? DEFAULT_RADIUS : Math.max(1, Math.min(requestedRadius, MAX_RADIUS));

        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
        String token = aesEncryptionUtil.decrypt(user.getGithubToken());

        String content = fetchFile(debt, token);
        if (content == null) {
            return DebtContextDto.builder()
                    .filePath(debt.getFilePath())
                    .commitSha(debt.getCommitSha())
                    .highlightLine(debt.getLineNo() == null ? 0 : debt.getLineNo())
                    .lines(List.of())
                    .tooLarge(true)
                    .build();
        }

        String[] all = content.split("\\r?\\n", -1);
        int total = all.length;
        int line = debt.getLineNo() == null ? 1 : Math.max(1, Math.min(debt.getLineNo(), total));
        int start = Math.max(1, line - radius);
        int end = Math.min(total, line + radius);

        List<String> lines = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            String text = all[i - 1];
            lines.add(text.length() > MAX_LINE_LENGTH ? text.substring(0, MAX_LINE_LENGTH) + "…" : text);
        }

        return DebtContextDto.builder()
                .filePath(debt.getFilePath())
                .commitSha(debt.getCommitSha())
                .startLine(start)
                .highlightLine(line)
                .totalLines(total)
                .lines(lines)
                .tooLarge(false)
                .build();
    }

    /** @return dosya içeriği; boyut sınırını aşıyorsa {@code null} */
    private String fetchFile(DebtDto debt, String token) {
        String repo = debt.getRepoFullName();
        String url = http.apiUrl() + "/repos/" + repo + "/contents/" + encodePath(debt.getFilePath())
                + (debt.getCommitSha() == null ? "" : "?ref=" + URLEncoder.encode(debt.getCommitSha(), StandardCharsets.UTF_8));

        HttpRequest request = http.request(URI.create(url), token)
                .header("Accept", "application/vnd.github.raw+json")
                .timeout(Duration.ofSeconds(20))
                .GET().build();

        HttpResponse<InputStream> response = http.send(request, dev.hashmark.common.resilience.GitHubResilience.Lane.INTERACTIVE);
        try (InputStream body = response.body()) {
            int status = response.statusCode();
            if (status == 200) {
                byte[] data = body.readNBytes(MAX_FILE_BYTES + 1);
                if (data.length > MAX_FILE_BYTES) {
                    return null;
                }
                return new String(data, StandardCharsets.UTF_8);
            }
            switch (status) {
                case 404 -> throw ApiException.notFound("File no longer exists at this commit");
                case 401 -> throw ApiException.badGateway("GitHub token is invalid or revoked");
                case 403 -> throw ApiException.forbidden("GitHub denied access to this file");
                default -> throw ApiException.badGateway("GitHub returned unexpected status " + status);
            }
        } catch (IOException e) {
            throw ApiException.badGateway("Could not read the file from GitHub");
        }
    }

    private static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/")) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }
}
