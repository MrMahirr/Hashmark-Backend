package dev.hashmark.debt;

import dev.hashmark.debt.dto.DebtFilterRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebtFilterRequestTest {

    @Test
    void clampsPageAndSize() {
        DebtFilterRequest f = DebtFilterRequest.builder().page(-5).size(1_000_000).build();

        assertEquals(0, f.safePage());
        assertEquals(DebtFilterRequest.MAX_PAGE_SIZE, f.safeSize());

        f.setSize(0);
        assertEquals(DebtFilterRequest.DEFAULT_PAGE_SIZE, f.safeSize());
        f.setSize(37);
        assertEquals(37, f.safeSize());
    }

    @Test
    void defaultsAreSafe() {
        DebtFilterRequest f = new DebtFilterRequest();

        assertEquals(0, f.safePage());
        assertEquals(20, f.safeSize());
        assertEquals("d.detected_at", f.sortColumn());
        assertEquals("DESC", f.sortDirection());
    }

    @Test
    void sortKeyIsWhitelisted() {
        DebtFilterRequest f = new DebtFilterRequest();

        f.setSort("filePath");
        assertEquals("d.file_path", f.sortColumn());

        f.setSort("id; DROP TABLE debts");
        assertEquals("d.detected_at", f.sortColumn());

        f.setDir("asc");
        assertEquals("ASC", f.sortDirection());
        f.setDir("sideways");
        assertEquals("DESC", f.sortDirection());
    }

    @Test
    void pathIsTrimmedOfSlashesAndBlankOrHugeIsIgnored() {
        DebtFilterRequest f = new DebtFilterRequest();
        assertEquals(null, f.safePath());

        f.setPath(" /src/api/ ");
        assertEquals("src/api", f.safePath());
        f.setPath("///");
        assertEquals(null, f.safePath());
        f.setPath("a".repeat(DebtFilterRequest.MAX_PATH_LENGTH + 1));
        assertEquals(null, f.safePath());
    }
}
