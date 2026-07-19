package dev.hashmark.scanner.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScanStartResponse {
    private Long repoId;
    private Long jobId;
    private String status;
}
