package dev.hashmark.scanner.service;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
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
        String url = "https://api.github.com/repos/" + repoFullName + "/git/trees/main?recursive=1";
        
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + githubToken);
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> response;
        try {
            response = restTemplate.exchange(url, HttpMethod.GET, request, new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (HttpClientErrorException.NotFound e) {
            // Fallback to master
            String masterUrl = "https://api.github.com/repos/" + repoFullName + "/git/trees/master?recursive=1";
            response = restTemplate.exchange(masterUrl, HttpMethod.GET, request, new ParameterizedTypeReference<Map<String, Object>>() {});
        }

        List<Map<String, Object>> tree = (List<Map<String, Object>>) response.getBody().get("tree");
        
        return tree.stream()
                .filter(item -> "blob".equals(item.get("type")))
                .map(item -> (String) item.get("path"))
                .filter(this::hasSupportedExtension)
                .collect(Collectors.toList());
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

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + githubToken);
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        String contentBase64 = (String) response.getBody().get("content");
        if (contentBase64 == null) {
            return "";
        }
        
        contentBase64 = contentBase64.replaceAll("\\r|\\n", "");
        byte[] decodedBytes = Base64.getDecoder().decode(contentBase64);
        return new String(decodedBytes, StandardCharsets.UTF_8);
    }
}
