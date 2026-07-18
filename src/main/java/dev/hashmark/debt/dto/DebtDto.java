package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtDto {
    private Long id;
    private Long repoId;
    private String repoFullName; // Eklenen alan
    private String filePath;
    private Integer lineNo;
    private String label;
    private String content;
    private LocalDateTime detectedAt;
    private LocalDateTime resolvedAt;
}
