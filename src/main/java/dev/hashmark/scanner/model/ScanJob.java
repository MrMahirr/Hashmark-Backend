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
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer debtFound;
    private LocalDateTime createdAt;
}
