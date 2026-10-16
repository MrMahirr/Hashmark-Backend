package dev.hashmark.auth.service;

import dev.hashmark.auth.dto.GitHubTokenResponse;
import dev.hashmark.auth.dto.GitHubUserDto;
import dev.hashmark.common.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

@Service
public class GitHubOAuthService {

    @Value("${github.client-id}")
    private String clientId;

    @Value("${github.client-secret}")
    private String clientSecret;

    @Value("${github.callback-url}")
    private String callbackUrl;

    /** İstenen OAuth kapsamı. Özel depo taraması için `repo` gerekir; yalnızca açık depolar için `public_repo`. */
    @Value("${github.oauth-scope:repo,user:email}")
    private String oauthScope;

    private final RestTemplate restTemplate;

    public GitHubOAuthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public String buildAuthorizationUrl(String state) {
        return UriComponentsBuilder.fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("scope", oauthScope)
                .queryParam("state", state)
                .encode()
                .build()
                .toUriString();
    }

    public String exchangeCodeForToken(String code) {
        String url = "https://github.com/login/oauth/access_token";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");

        Map<String, String> requestBody = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", callbackUrl
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(requestBody, headers);
        
        ResponseEntity<GitHubTokenResponse> response = restTemplate.postForEntity(url, request, GitHubTokenResponse.class);
        GitHubTokenResponse responseBody = response.getBody();

        if (responseBody != null && responseBody.getAccessToken() != null && !responseBody.getAccessToken().isBlank()) {
            return responseBody.getAccessToken();
        }

        String message = responseBody != null && responseBody.getErrorDescription() != null
                ? responseBody.getErrorDescription()
                : "Failed to exchange GitHub authorization code";
        throw ApiException.unauthorized(message);
    }

    public GitHubUserDto fetchUserInfo(String accessToken) {
        String url = "https://api.github.com/user";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<GitHubUserDto> response = restTemplate.exchange(url, HttpMethod.GET, request, GitHubUserDto.class);
        GitHubUserDto body = response.getBody();
        if (body == null) {
            throw ApiException.unauthorized("GitHub user response was empty");
        }
        return body;
    }
}
