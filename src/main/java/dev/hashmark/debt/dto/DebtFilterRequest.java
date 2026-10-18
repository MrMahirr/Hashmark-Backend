package dev.hashmark.debt.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtFilterRequest {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** İzin verilen sıralama anahtarları → SQL sütunu (kullanıcı girdisi SQL'e asla doğrudan girmez). */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "detectedAt", "d.detected_at",
            "resolvedAt", "d.resolved_at",
            "filePath", "d.file_path",
            "lineNo", "d.line_no",
            "label", "d.label",
            "dueDate", "d.due_date",
            "introducedAt", "d.introduced_at",
            "priority", "d.priority"
    );

    private Long repoId;
    private String label;
    /** open | resolved | ignored (boş = hepsi). */
    private String status;
    /** Dosya yolu veya içerikte arama (büyük/küçük harf duyarsız). */
    private String q;
    private String assignee;
    private String priority;
    /** Borcu ekleyen GitHub kullanıcısı (git blame, {@code author_login}); büyük/küçük harf duyarsız. */
    private String author;
    /** Yalnızca en az bu kadar gündür var olan borçlar (yaş = blame tarihi, yoksa ilk görülme). */
    private Integer olderThanDays;

    public static final int MAX_AGE_DAYS = 36_500;

    /** Negatif / aşırı değer yok sayılır. */
    public Integer safeOlderThanDays() {
        return olderThanDays == null || olderThanDays < 0 ? null : Math.min(olderThanDays, MAX_AGE_DAYS);
    }
    /** detectedAt (varsayılan), resolvedAt, filePath, lineNo, label, dueDate, introducedAt, priority */
    private String sort;
    /** asc | desc (varsayılan desc) */
    private String dir;
    @Builder.Default
    private int page = 0;
    @Builder.Default
    private int size = DEFAULT_PAGE_SIZE;

    /** Sayfa ve boyutu güvenli aralığa çeker (büyük {@code size} ile kaynak tüketimini önler). */
    public int safePage() {
        return Math.max(0, page);
    }

    public int safeSize() {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    public String sortColumn() {
        String column = sort == null ? null : SORT_COLUMNS.get(sort);
        return column != null ? column : "d.detected_at";
    }

    public String sortDirection() {
        return "asc".equalsIgnoreCase(dir) ? "ASC" : "DESC";
    }
}
