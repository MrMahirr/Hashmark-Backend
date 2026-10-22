package dev.hashmark.report.service;

import dev.hashmark.report.dto.HotspotResponse;
import dev.hashmark.report.repository.ReportRepository.HotspotRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotspotCalculatorTest {

    private static final List<HotspotRow> ROWS = List.of(
            new HotspotRow("src/api/Users.java", "TODO", null, 3, 1),
            new HotspotRow("src/api/Users.java", "FIXME", "CRITICAL", 1, 0),
            new HotspotRow("src/db/Repo.java", "HACK", null, 2, 2),
            new HotspotRow("src/Main.java", "TODO", "LOW", 4, 0),
            new HotspotRow("README.md", "TODO", null, 1, 0));

    @Test
    void normalizesUserPaths() {
        assertEquals("", HotspotCalculator.normalizePath(null));
        assertEquals("", HotspotCalculator.normalizePath(" / "));
        assertEquals("src/api", HotspotCalculator.normalizePath("/src//api/"));
        // "." ve ".." anlam taşımaz, atılır (yol yalnızca LIKE önekidir)
        assertEquals("src/api", HotspotCalculator.normalizePath("src/../api"));
        assertEquals("a/b", HotspotCalculator.normalizePath("./a/./b"));
        assertEquals("", HotspotCalculator.normalizePath("a".repeat(501)));
    }

    @Test
    void rootGroupsByTopLevelChildAndRanksByWeight() {
        HotspotResponse map = HotspotCalculator.build("", ROWS, false);

        assertEquals(11, map.debts());
        assertEquals(3, map.staleCount());
        // TODO 3 + FIXME 1×2×2 + HACK 2×2 + TODO 4×0.75 + TODO 1
        assertEquals(15.0, map.weightedDebt());

        assertEquals(List.of("src", "README.md"), map.children().stream().map(HotspotResponse.Node::name).toList());
        HotspotResponse.Node src = map.children().get(0);
        assertTrue(src.directory());
        assertEquals("src", src.path());
        assertEquals(10, src.debts());
        assertEquals(3, src.files());
        assertFalse(map.children().get(1).directory());

        // En sıcak dosya: Users.java (3 + 4 = 7)
        assertEquals("src/api/Users.java", map.topFiles().get(0).path());
        assertEquals(7.0, map.topFiles().get(0).weightedDebt());
        assertEquals(1, map.topFiles().get(0).files());
    }

    @Test
    void subfolderListsDirectChildrenOnly() {
        HotspotResponse map = HotspotCalculator.build("src", ROWS, false);

        assertEquals("src", map.path());
        assertEquals(List.of("api", "db", "Main.java"), map.children().stream().map(HotspotResponse.Node::name).toList());
        assertEquals("src/api", map.children().get(0).path());
        assertEquals(3, map.topFiles().size());
        assertEquals(10, map.debts());
    }

    @Test
    void sameNameFileAndFolderStaySeparateAndChildrenAreCapped() {
        List<HotspotRow> rows = new ArrayList<>(List.of(
                new HotspotRow("x/a", "TODO", null, 1, 0),
                new HotspotRow("x/a/b.js", "TODO", null, 1, 0)));
        for (int i = 0; i < HotspotCalculator.MAX_CHILDREN + 5; i++) {
            rows.add(new HotspotRow("y/f" + i + ".js", "TODO", null, 1, 0));
        }

        assertEquals(2, HotspotCalculator.build("x", rows, false).children().size());
        HotspotResponse y = HotspotCalculator.build("y", rows, true);
        assertEquals(HotspotCalculator.MAX_CHILDREN, y.children().size());
        assertEquals(5, y.hiddenChildren());
        assertEquals(HotspotCalculator.TOP_FILES, y.topFiles().size());
        assertTrue(y.truncated());
    }
}
