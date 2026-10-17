package dev.hashmark.scanner.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.dto.BlameRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** GitHub GraphQL {@code blame} sorgusuyla bir dosyanın satır başına yazar/commit bilgisini getirir. */
@Service
public class GitHubBlameService {

    private static final Logger log = LoggerFactory.getLogger(GitHubBlameService.class);

    private static final String QUERY = """
            query($owner: String!, $name: String!, $oid: GitObjectID!, $path: String!) {
              repository(owner: $owner, name: $name) {
                object(oid: $oid) {
                  ... on Commit {
                    blame(path: $path) {
                      ranges {
                        startingLine
                        endingLine
                        commit { oid authoredDate author { name user { login } } }
                      }
                    }
                  }
                }
              }
            }
            """;

    private final GitHubHttpClient http;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public GitHubBlameService(GitHubHttpClient http) {
        this.http = http;
    }

    /**
     * @return aralıklar; sorgu başarısız olduysa (yetki, limit, ağ) {@link Optional#empty()}
     */
    public Optional<List<BlameRange>> fetchBlame(String repoFullName, String token, String commitSha, String path) {
        int slash = repoFullName.indexOf('/');
        if (slash < 0) {
            return Optional.empty();
        }
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "query", QUERY,
                    "variables", Map.of(
                            "owner", repoFullName.substring(0, slash),
                            "name", repoFullName.substring(slash + 1),
                            "oid", commitSha,
                            "path", path)));

            HttpRequest request = http.request(URI.create(http.apiUrl() + "/graphql"), token)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<InputStream> response = http.send(request);
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    return Optional.empty();
                }
                return parse(mapper.readTree(in));
            }
        } catch (ApiException e) {
            log.info("Blame skipped for {}:{} ({})", repoFullName, path, e.getMessage());
            return Optional.empty();
        } catch (IOException | JacksonException e) {
            log.info("Blame failed for {}:{}", repoFullName, path, e);
            return Optional.empty();
        }
    }

    static Optional<List<BlameRange>> parse(JsonNode root) {
        if (root == null || root.hasNonNull("errors")) {
            return Optional.empty();
        }
        JsonNode ranges = root.path("data").path("repository").path("object").path("blame").path("ranges");
        if (!ranges.isArray()) {
            return Optional.empty();
        }
        List<BlameRange> result = new ArrayList<>();
        for (JsonNode node : ranges) {
            JsonNode commit = node.path("commit");
            JsonNode author = commit.path("author");
            result.add(new BlameRange(
                    node.path("startingLine").asInt(),
                    node.path("endingLine").asInt(),
                    commit.path("oid").asText(null),
                    parseDate(commit.path("authoredDate").asText(null)),
                    author.path("user").path("login").asText(null),
                    author.path("name").asText(null)));
        }
        return Optional.of(result);
    }

    private static LocalDateTime parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
