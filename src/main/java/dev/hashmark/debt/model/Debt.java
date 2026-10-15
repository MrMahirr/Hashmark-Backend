package dev.hashmark.debt.model;

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
public class Debt {
    private Long id;
    private Long repoId;
    private String filePath;
    private Integer lineNo;
    private String label;
    private String content;
    private LocalDateTime detectedAt;
    private LocalDateTime resolvedAt;

    // Kimlik: aynı dosyada aynı (etiket + içerik) için sıra numarası ile birlikte benzersizdir.
    private String fingerprint;
    private Integer occurrence;

    // Parser üst verisi (her taramada yeniden yazılır)
    private String parsedAssignee;
    private String priority;
    private LocalDate dueDate;
    private String issueRef;
}
