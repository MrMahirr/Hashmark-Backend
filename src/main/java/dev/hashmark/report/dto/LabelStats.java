package dev.hashmark.report.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.TreeMap;

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
    /** .hashmark.yml özel etiketleri (etiket → açık borç sayısı); hepsi borç sayılır. */
    @Builder.Default
    private Map<String, Integer> customCounts = new TreeMap<>();

    public LabelStats(int todoCount, int fixmeCount, int hackCount, int xxxCount, int noteCount, int docCount,
                      int infoCount) {
        this(todoCount, fixmeCount, hackCount, xxxCount, noteCount, docCount, infoCount, new TreeMap<>());
    }
}
