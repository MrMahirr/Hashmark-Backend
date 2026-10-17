package dev.hashmark.repo.service;

import dev.hashmark.repo.dto.GitHubRepoDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Service
public class GitHubRepoService {

    @org.springframework.beans.factory.annotation.Value("${github.api-url:https://api.github.com}")
    private String apiUrl = "https://api.github.com";

    private final RestTemplate restTemplate;

    public GitHubRepoService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public List<GitHubRepoDto> fetchUserRepos(String githubToken) {
        String url = apiUrl + "/user/repos?per_page=100&visibility=all";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + githubToken);
        headers.set("Accept", "application/vnd.github.v3+json");

        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<List<GitHubRepoDto>> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                request,
                new ParameterizedTypeReference<>() {}
        );

        List<GitHubRepoDto> body = response.getBody();
        return body != null ? body : List.of();
    }
}
