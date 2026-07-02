package dev.hashmark.auth.service;

import dev.hashmark.auth.dto.GitHubUserDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

@Service
public class GitHubOAuthService {

    @Value("${github.client-id}")
    private String clientId;

    @Value("${github.client-secret}")
    private String clientSecret;

    @Value("${github.callback-url}")
    private String callbackUrl;

    private final RestTemplate restTemplate;

    public GitHubOAuthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String buildAuthorizationUrl() {
        return "https://github.com/login/oauth/authorize" +
                "?client_id=" + clientId +
                "&redirect_uri=" + callbackUrl +
                "&scope=repo,user:email" +
                "&state=" + UUID.randomUUID();
    }

    public String exchangeCodeForToken(String code) {
        String url = "https://github.com/login/oauth/access_token";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        Map<String, String> body = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", callbackUrl
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);
        
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
        
        if (response.getBody() != null && response.getBody().containsKey("access_token")) {
            return (String) response.getBody().get("access_token");
        }
        
        throw new RuntimeException("Failed to exchange code for token: " + response.getBody());
    }

    public GitHubUserDto fetchUserInfo(String accessToken) {
        String url = "https://api.github.com/user";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<GitHubUserDto> response = restTemplate.exchange(url, HttpMethod.GET, request, GitHubUserDto.class);

        return response.getBody();
    }
}
