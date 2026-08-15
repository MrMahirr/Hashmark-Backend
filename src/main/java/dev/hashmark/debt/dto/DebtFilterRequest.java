package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtFilterRequest {
    private Long repoId;
    private String label;
    private String status; // "open" veya "resolved"
    @Builder.Default
    private int page = 0;
    @Builder.Default
    private int size = 20;
}
