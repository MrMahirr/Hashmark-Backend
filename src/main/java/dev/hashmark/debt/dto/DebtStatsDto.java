package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtStatsDto {
    private int total;
    private int addedThisWeek;
    private int resolvedThisWeek;
}
