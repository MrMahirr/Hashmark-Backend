package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.dto.GitHubContentResponse;
import dev.hashmark.scanner.dto.GitHubRepositoryMetadata;
import dev.hashmark.scanner.dto.GitHubTreeItem;
import dev.hashmark.scanner.dto.GitHubTreeResponse;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class GitHubFileService {

    private final RestTemplate restTemplate;

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            ".java", ".ts", ".tsx", ".js", ".jsx", ".py", ".go", ".rb", ".rs", ".kt", ".swift", ".cs", ".cpp", ".c", ".h"
    );

    public GitHubFileService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public List<String> listRepoFiles(String repoFullName, String githubToken) {
        HttpEntity<Void> request = new HttpEntity<>(buildGitHubHeaders(githubToken));
        String defaultBranch = fetchDefaultBranch(repoFullName, request);
        String url = "https://api.github.com/repos/" + repoFullName + "/git/trees/" + defaultBranch + "?recursive=1";

        ResponseEntity<GitHubTreeResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<>() {}
        );

        GitHubTreeResponse body = response.getBody();
        if (body == null || body.getTree() == null) {
            return List.of();
        }
        
        return body.getTree().stream()
                .filter(item -> "blob".equals(item.getType()))
                .map(GitHubTreeItem::getPath)
                .filter(this::hasSupportedExtension)
                .collect(Collectors.toList());
    }

    private String fetchDefaultBranch(String repoFullName, HttpEntity<Void> request) {
        String url = "https://api.github.com/repos/" + repoFullName;
        ResponseEntity<GitHubRepositoryMetadata> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                GitHubRepositoryMetadata.class
        );

        GitHubRepositoryMetadata metadata = response.getBody();
        if (metadata == null || metadata.getDefaultBranch() == null || metadata.getDefaultBranch().isBlank()) {
            throw ApiException.badRequest("GitHub repository default branch could not be determined");
        }
        return metadata.getDefaultBranch();
    }

    private boolean hasSupportedExtension(String path) {
        if (path == null) return false;
        int lastDotIndex = path.lastIndexOf('.');
        if (lastDotIndex == -1) return false;
        String ext = path.substring(lastDotIndex);
        return SUPPORTED_EXTENSIONS.contains(ext.toLowerCase());
    }

    public String getFileContent(String repoFullName, String filePath, String githubToken) {
        String url = "https://api.github.com/repos/" + repoFullName + "/contents/" + filePath;

        HttpEntity<Void> request = new HttpEntity<>(buildGitHubHeaders(githubToken));

        ResponseEntity<GitHubContentResponse> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                GitHubContentResponse.class
        );

        GitHubContentResponse body = response.getBody();
        String contentBase64 = body != null ? body.getContent() : null;
        if (contentBase64 == null) {
            return "";
        }
        
        contentBase64 = contentBase64.replace("\r", "").replace("\n", "");
        byte[] decodedBytes = Base64.getDecoder().decode(contentBase64);
        return new String(decodedBytes, StandardCharsets.UTF_8);
    }

    private HttpHeaders buildGitHubHeaders(String githubToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + githubToken);
        headers.set("Accept", "application/vnd.github.v3+json");
        return headers;
    }
}
