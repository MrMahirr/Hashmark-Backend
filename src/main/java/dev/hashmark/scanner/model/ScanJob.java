package dev.hashmark.scanner.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanJob {
    private Long id;
    private Long repoId;
    private Long userId;
    private String status;
    private String branch;
    private String commitSha;
    private Integer attempts;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer debtFound;
    private Integer filesScanned;
    private Integer filesSkipped;
    private Integer filesFailed;
    private String message;
    private LocalDateTime createdAt;
}
