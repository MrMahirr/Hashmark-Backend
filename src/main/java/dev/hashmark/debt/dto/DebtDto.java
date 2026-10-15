package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
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

    // Parser'ın etiketten çıkardığı üst veriler (TODO(mahir): ..., FIXME[P1], TODO 2026-12-01).
    // Henüz veritabanında saklanmıyor; yalnızca tarama sırasında doldurulur.
    private String assignee;
    private String priority;
    private LocalDate dueDate;
    private String issueRef;
}
