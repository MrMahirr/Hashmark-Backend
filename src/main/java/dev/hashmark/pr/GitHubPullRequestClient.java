package dev.hashmark.pr;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.service.GitHubHttpClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static dev.hashmark.scanner.service.GitHubHttpClient.closeQuietly;

/**
 * PR borç kontrolünün GitHub çağrıları. Kuyruk işçisinde çalışır (tarama şeridi: rate-limit için makul bekleme,
 * 5xx'te yeniden deneme). Kullanıcının OAuth token'ı ile yazar: yorum ve durum o kullanıcı adına görünür.
 */
@Component
public class GitHubPullRequestClient {

    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private static final int COMMENT_PAGES = 3;

    private final GitHubHttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public GitHubPullRequestClient(GitHubHttpClient http) {
        this.http = http;
    }

    /** @param patch {@code null} ise GitHub diff'i göstermedi (çok büyük / ikili) */
    public record ChangedFile(String path, String status, String patch) {
    }

    /** @param truncated GitHub dosya listesini kesti (300+ dosya) */
    public record Comparison(List<ChangedFile> files, boolean truncated) {
    }

    public static boolean isSha(String value) {
        return value != null && SHA.matcher(value).matches();
    }

    /** {@code base...head} (birleşme tabanına göre) değişen dosyalar. */
    public Comparison compare(String repo, String token, String baseSha, String headSha) {
        requireSha(baseSha);
        requireSha(headSha);
        JsonNode root = getJson(repo, token, "/compare/" + baseSha + "..." + headSha, "Could not compare commits");
        List<ChangedFile> files = new ArrayList<>();
        for (JsonNode f : root.path("files")) {
            String patch = f.hasNonNull("patch") ? f.path("patch").asText() : null;
            files.add(new ChangedFile(f.path("filename").asText(""), f.path("status").asText(""), patch));
        }
        // GitHub compare yanıtında en fazla 300 dosya listeler
        return new Comparison(files, files.size() >= 300);
    }

    /** Dosyanın o commit'teki içeriği; yoksa, çok büyükse ya da okunamazsa boş. */
    public Optional<String> fileAt(String repo, String token, String path, String sha, long maxBytes) {
        requireSha(sha);
        String url = http.apiUrl() + "/repos/" + repo + "/contents/" + encodePath(path) + "?ref=" + sha;
        HttpResponse<InputStream> response = http.send(http.request(URI.create(url), token)
                .header("Accept", "application/vnd.github.raw+json")
                .timeout(Duration.ofSeconds(30))
                .GET().build());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            byte[] data = body.readNBytes((int) Math.min(Integer.MAX_VALUE - 1, maxBytes + 1));
            if (data.length > maxBytes) {
                return Optional.empty();
            }
            for (int i = 0; i < Math.min(data.length, 8000); i++) {
                if (data[i] == 0) {
                    return Optional.empty(); // ikili
                }
            }
            return Optional.of(new String(data, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** PR'ın şu anki head commit'i; PR yoksa / erişilemiyorsa boş. */
    public Optional<String> currentHead(String repo, String token, int prNumber) {
        try {
            String sha = getJson(repo, token, "/pulls/" + prNumber, "Could not read pull request")
                    .path("head").path("sha").asText("");
            return isSha(sha) ? Optional.of(sha) : Optional.empty();
        } catch (ApiException e) {
            if (e.isTransient()) {
                throw e;
            }
            return Optional.empty();
        }
    }

    /** @param state success | failure | error | pending */
    public void setStatus(String repo, String token, String sha, String state, String description,
                          String targetUrl, String context) {
        requireSha(sha);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("state", state);
        body.put("description", description.length() > 140 ? description.substring(0, 139) + "…" : description);
        body.put("context", context);
        if (targetUrl != null) {
            body.put("target_url", targetUrl);
        }
        send("POST", repo, token, "/statuses/" + sha, body, "Could not set commit status", 201);
    }

    /**
     * {@code marker} içeren yorumu günceller; yoksa ve {@code createIfMissing} ise oluşturur.
     *
     * @return yorum yazıldıysa true
     */
    public boolean upsertComment(String repo, String token, int prNumber, String marker, String body,
                                 boolean createIfMissing) {
        Optional<Long> existing = findComment(repo, token, prNumber, marker);
        if (existing.isPresent()) {
            send("PATCH", repo, token, "/issues/comments/" + existing.get(), Map.of("body", body),
                    "Could not update pull request comment", 200);
            return true;
        }
        if (!createIfMissing) {
            return false;
        }
        send("POST", repo, token, "/issues/" + prNumber + "/comments", Map.of("body", body),
                "Could not comment on pull request", 201);
        return true;
    }

    Optional<Long> findComment(String repo, String token, int prNumber, String marker) {
        for (int page = 1; page <= COMMENT_PAGES; page++) {
            JsonNode comments = getJson(repo, token, "/issues/" + prNumber + "/comments?per_page=100&page=" + page,
                    "Could not read pull request comments");
            for (JsonNode c : comments) {
                if (c.path("body").asText("").contains(marker)) {
                    return Optional.of(c.path("id").asLong());
                }
            }
            if (comments.size() < 100) {
                break;
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------

    private JsonNode getJson(String repo, String token, String suffix, String failure) {
        HttpResponse<InputStream> response = http.send(http.request(URI.create(http.apiUrl() + "/repos/" + repo + suffix), token)
                .timeout(Duration.ofSeconds(30))
                .GET().build());
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw failure(response.statusCode(), failure);
        }
        try (InputStream body = response.body()) {
            return mapper.readTree(body.readNBytes(MAX_JSON_BYTES));
        } catch (IOException | JacksonException e) {
            throw ApiException.badGateway(failure);
        }
    }

    private void send(String method, String repo, String token, String suffix, Object payload, String failure,
                      int expected) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize request", e);
        }
        HttpResponse<InputStream> response = http.send(http.request(URI.create(http.apiUrl() + "/repos/" + repo + suffix), token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build());
        int status = response.statusCode();
        closeQuietly(response.body());
        if (status != expected) {
            throw failure(status, failure);
        }
    }

    private static ApiException failure(int status, String message) {
        return switch (status) {
            case 401 -> ApiException.unauthorized("GitHub token is invalid or revoked, please sign in again");
            case 403, 404 -> ApiException.forbidden(message + ": GitHub denied access");
            case 422 -> ApiException.badRequest(message + ": GitHub rejected the request");
            default -> ApiException.badGateway(message + ": GitHub returned status " + status);
        };
    }

    private static void requireSha(String sha) {
        if (!isSha(sha)) {
            throw ApiException.badRequest("Invalid commit sha");
        }
    }

    /** Yol parçalarını kodlar, '/' ayraçlarını korur ({@code ..} ve boş parça reddedilir). */
    static String encodePath(String path) {
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) {
                throw ApiException.badRequest("Invalid file path");
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }
}
