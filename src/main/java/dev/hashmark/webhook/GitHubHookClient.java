package dev.hashmark.webhook;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.resilience.GitHubResilience.Lane;
import dev.hashmark.scanner.service.GitHubHttpClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dev.hashmark.scanner.service.GitHubHttpClient.closeQuietly;

/**
 * GitHub depo hook'ları ({@code /repos/{owner}/{repo}/hooks}). Kullanıcının OAuth token'ı ile çağrılır;
 * {@code repo} kapsamı hook yönetimini içerir ama depoda <b>admin</b> yetkisi gerekir. Kullanıcı isteğiyle çalışır:
 * etkileşimli şerit (hızlı başarısızlık, rate-limit için beklemez, tarama işçilerinin iznini tüketmez).
 */
@Component
public class GitHubHookClient {

    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    private final GitHubHttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public GitHubHookClient(GitHubHttpClient http) {
        this.http = http;
    }

    /**
     * Yalnızca {@code push} olayına abone, JSON gövdeli hook oluşturur. Aynı adreste hook zaten varsa (422)
     * onu bulup anahtarını günceller.
     *
     * @return hook kimliği
     */
    public long createOrAdopt(String repoFullName, String token, String url, String secret) {
        String body = json(Map.of(
                "name", "web",
                "active", true,
                "events", List.of("push"),
                "config", config(url, secret)));
        HttpResponse<InputStream> response = http.send(http.request(hooksUri(repoFullName, ""), token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), Lane.INTERACTIVE);
        int status = response.statusCode();
        if (status == 201) {
            return readId(response);
        }
        closeQuietly(response.body());
        if (status == 422) {
            long existing = findByUrl(repoFullName, token, url)
                    .orElseThrow(() -> ApiException.badGateway("GitHub rejected the webhook configuration"));
            if (!updateSecret(repoFullName, token, existing, url, secret)) {
                throw ApiException.badGateway("Could not update the existing webhook on GitHub");
            }
            return existing;
        }
        throw failure(status);
    }

    /** @return hook hâlâ varsa ve güncellendiyse true; GitHub'da silinmişse (404) false */
    public boolean updateSecret(String repoFullName, String token, long hookId, String url, String secret) {
        String body = json(Map.of("active", true, "events", List.of("push"), "config", config(url, secret)));
        HttpResponse<InputStream> response = http.send(http.request(hooksUri(repoFullName, "/" + hookId), token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build(), Lane.INTERACTIVE);
        int status = response.statusCode();
        closeQuietly(response.body());
        if (status == 200) {
            return true;
        }
        if (status == 404) {
            return false;
        }
        throw failure(status);
    }

    /** @return silindiyse ya da zaten yoksa true; yetki yoksa false */
    public boolean delete(String repoFullName, String token, long hookId) {
        HttpResponse<InputStream> response = http.send(http.request(hooksUri(repoFullName, "/" + hookId), token)
                .timeout(Duration.ofSeconds(20))
                .DELETE()
                .build(), Lane.INTERACTIVE);
        int status = response.statusCode();
        closeQuietly(response.body());
        return status == 204 || status == 404;
    }

    Optional<Long> findByUrl(String repoFullName, String token, String url) {
        HttpResponse<InputStream> response = http.send(
                http.request(hooksUri(repoFullName, "?per_page=100"), token)
                        .timeout(Duration.ofSeconds(20))
                        .GET().build(), Lane.INTERACTIVE);
        if (response.statusCode() != 200) {
            closeQuietly(response.body());
            throw failure(response.statusCode());
        }
        for (JsonNode hook : readJson(response)) {
            if (url.equals(hook.path("config").path("url").asText(""))) {
                return Optional.of(hook.path("id").asLong());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------

    private URI hooksUri(String repoFullName, String suffix) {
        return URI.create(http.apiUrl() + "/repos/" + repoFullName + "/hooks" + suffix);
    }

    private static Map<String, Object> config(String url, String secret) {
        return Map.of("url", url, "content_type", "json", "secret", secret, "insecure_ssl", "0");
    }

    private static ApiException failure(int status) {
        return switch (status) {
            case 401 -> ApiException.unauthorized("GitHub token is invalid or revoked, please sign in again");
            // GitHub, yetkisiz kullanıcıya hook uçlarında 404 döner
            case 403, 404 -> ApiException.forbidden(
                    "Admin access to the repository is required to enable auto-scan");
            default -> ApiException.badGateway("GitHub returned unexpected status " + status);
        };
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize webhook request", e);
        }
    }

    private long readId(HttpResponse<InputStream> response) {
        long id = readJson(response).path("id").asLong(0);
        if (id <= 0) {
            throw ApiException.badGateway("GitHub did not return a webhook id");
        }
        return id;
    }

    private JsonNode readJson(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            return mapper.readTree(body.readNBytes(MAX_RESPONSE_BYTES));
        } catch (IOException | JacksonException e) {
            throw ApiException.badGateway("Could not read GitHub webhook response");
        }
    }
}
