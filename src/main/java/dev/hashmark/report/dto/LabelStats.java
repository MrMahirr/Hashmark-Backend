package dev.hashmark.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabelStats {
    private int todoCount;
    private int fixmeCount;
    private int hackCount;
    private int xxxCount;
    private int noteCount;
    private int docCount;
    private int infoCount;
}
