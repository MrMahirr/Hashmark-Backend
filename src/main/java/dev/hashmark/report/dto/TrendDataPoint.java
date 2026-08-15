package dev.hashmark.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendDataPoint {
    private LocalDate weekStart;
    private int totalDebts;
    private int newDebts;
    private int resolvedDebts;
}
