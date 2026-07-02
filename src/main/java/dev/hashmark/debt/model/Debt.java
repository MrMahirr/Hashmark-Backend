package dev.hashmark.debt.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Debt {
    private Long id;
    private Long repoId;
    private String filePath;
    private Integer lineNo;
    private String label;
    private String content;
    private LocalDateTime detectedAt;
    private LocalDateTime resolvedAt;
}
