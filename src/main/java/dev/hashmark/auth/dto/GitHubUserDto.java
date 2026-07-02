package dev.hashmark.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class GitHubUserDto {
    private String id;
    private String login;
    private String email;
    private String name;
    
    @JsonProperty("avatar_url")
    private String avatarUrl;
}
