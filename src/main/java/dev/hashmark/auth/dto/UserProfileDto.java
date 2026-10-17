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
    /** GitHub erişim jetonu kayıtlı mı (bağlantı kesildiyse false). */
    private boolean githubConnected;
    private LocalDateTime createdAt;
    /** Onay kapısı açık ve geçerli sürüme onay yok: istemci /consent sayfasına yönlendirmeli. */
    private boolean consentRequired;
    private Integer consentVersion;
    private LocalDateTime consentAcceptedAt;
}
