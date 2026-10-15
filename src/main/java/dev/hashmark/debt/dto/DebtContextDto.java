package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Bir borcun çevresindeki kaynak kodu satırları (önizleme). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtContextDto {
    private String filePath;
    /** Satırların okunduğu commit; bilinmiyorsa null (varsayılan dal). */
    private String commitSha;
    /** {@code lines[0]} dosyanın bu satırıdır (1 tabanlı). */
    private int startLine;
    /** Borcun bulunduğu satır numarası. */
    private int highlightLine;
    private int totalLines;
    private List<String> lines;
    /** Dosya önizleme sınırını aştığı için içerik dönmediyse true. */
    private boolean tooLarge;
}
