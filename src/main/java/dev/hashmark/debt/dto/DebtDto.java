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
    private String repoFullName;
    private String filePath;
    private Integer lineNo;
    private String label;
    private String content;
    private LocalDateTime detectedAt;
    private LocalDateTime resolvedAt;

    /** Taramada son görüldüğü commit; permalink bu commit'e işaret eder. */
    private String commitSha;
    private String permalink;
    private String fingerprint;

    /** OPEN, IGNORED veya WONT_FIX (kullanıcı kararı; tarama bunu değiştirmez). */
    private String triageStatus;

    /** Etkin atanan: elle atama varsa o, yoksa kodda {@code TODO(kisi)} ile yazılan. */
    private String assignee;
    private String priority;
    private LocalDate dueDate;
    private String issueRef;

    // git blame (en iyi çaba; boş olabilir)
    private String authorLogin;
    private String authorName;
    private LocalDateTime introducedAt;
}
