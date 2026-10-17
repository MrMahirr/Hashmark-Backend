package dev.hashmark.repo.model;

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
public class Repo {
    private Long id;
    private Long userId;
    private String githubRepoId;
    private String fullName;
    private Boolean isPrivate;
    private LocalDateTime lastScannedAt;
    private LocalDateTime createdAt;
    private String trackedBranch;
    private String lastScannedSha;
    /** İzlenen (taranan) depo mu; false = yalnızca GitHub'dan gelen aday. */
    private Boolean tracked;
    /** GitHub'ın bildirdiği depo boyutu (KB); bilinmiyorsa null. */
    private Integer sizeKb;

    // Transient / calculated field
    private Integer debtCount;
    /** Açık borçların etiket kırılımı (yalnızca liste/ayrıntı uçlarında dolar). */
    private Map<String, Integer> debtBreakdown;
}
