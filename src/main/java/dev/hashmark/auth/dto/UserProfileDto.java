package dev.hashmark.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserProfileDto {
    private String name;
    private String email;
    private String githubLogin;
    private String avatarUrl;
    private LocalDateTime createdAt;
}
