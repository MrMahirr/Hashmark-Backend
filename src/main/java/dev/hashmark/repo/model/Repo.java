package dev.hashmark.repo.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Repo {
    private Long id;
    private Long userId;
    private String githubRepoId;
    private String fullName;
    private Boolean isPrivate;
    private LocalDateTime lastScannedAt;
    private LocalDateTime createdAt;
    
    // Transient / calculated field
    private Integer debtCount;
}
