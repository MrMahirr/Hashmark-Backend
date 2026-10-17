package dev.hashmark.repo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoDto {
    private Long id;
    private Long userId;
    private String githubRepoId;
    private String fullName;
    private Boolean isPrivate;
    private LocalDateTime lastScannedAt;
    private LocalDateTime createdAt;
    private Integer debtCount;
    private String trackedBranch;
    private String lastScannedSha;
    private Boolean tracked;
    /** Push webhook'u kurulu: izlenen dala her push'ta otomatik tarama. */
    private Boolean autoScan;
    /** Açık borçların etiket kırılımı: {"TODO": 4, "FIXME": 1}. */
    private Map<String, Integer> debtBreakdown;
}
