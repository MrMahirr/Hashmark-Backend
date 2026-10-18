package dev.hashmark.issue;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.hashmark.scanner.service.GitHubHttpClient.closeQuietly;

/** GitHub issue oluşturma (kullanıcı isteği → etkileşimli şerit; issue kullanıcının adıyla açılır). */
@Component
public class GitHubIssueClient {

    private final GitHubHttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public GitHubIssueClient(GitHubHttpClient http) {
        this.http = http;
    }

    public record CreatedIssue(int number, String url) {
    }

    /**
     * Etiketler yalnızca depoya yazma yetkisi olanlarca eklenebilir; GitHub etiketleri reddederse (422) issue
     * etiketsiz yeniden denenir.
     */
    public CreatedIssue create(String repo, String token, String title, String body, List<String> labels) {
        HttpResponse<InputStream> response = post(repo, token, title, body, labels);
        if (response.statusCode() == 422 && !labels.isEmpty()) {
            closeQuietly(response.body());
            response = post(repo, token, title, body, List.of());
        }
        int status = response.statusCode();
        if (status != 201) {
            closeQuietly(response.body());
            throw switch (status) {
                case 401 -> ApiException.unauthorized("GitHub token is invalid or revoked, please sign in again");
                case 403, 404 -> ApiException.forbidden("GitHub denied creating an issue in this repository");
                case 410 -> ApiException.badRequest("Issues are disabled for this repository");
                default -> ApiException.badGateway("GitHub returned unexpected status " + status);
            };
        }
        try (InputStream in = response.body()) {
            JsonNode node = mapper.readTree(in.readNBytes(1024 * 1024));
            int number = node.path("number").asInt(0);
            if (number <= 0) {
                throw ApiException.badGateway("GitHub did not return an issue number");
            }
            return new CreatedIssue(number, node.path("html_url").asText(null));
        } catch (IOException | JacksonException e) {
            throw ApiException.badGateway("Could not read GitHub issue response");
        }
    }

    private HttpResponse<InputStream> post(String repo, String token, String title, String body, List<String> labels) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body);
        if (!labels.isEmpty()) {
            payload.put("labels", labels);
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not serialize issue", e);
        }
        return http.send(http.request(URI.create(http.apiUrl() + "/repos/" + repo + "/issues"), token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(), Lane.INTERACTIVE);
    }
}
