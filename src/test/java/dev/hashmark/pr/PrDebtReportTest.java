package dev.hashmark.pr;

import dev.hashmark.pr.PrDebtReport.NewDebt;
import dev.hashmark.scanner.config.RepoScanConfig.PrSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrDebtReportTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static PrDebtReport report(List<NewDebt> debts, PrSettings settings) {
        return new PrDebtReport("o/r", 7, SHA, debts, 0, settings);
    }

    private static final List<NewDebt> THREE = List.of(
            new NewDebt("TODO", "src/a.ts", 3, "later"),
            new NewDebt("FIXME", "src/a.ts", 9, "broken"),
            new NewDebt("FIXME", "src/b.ts", 1, "also broken"));

    @Test
    void withoutBudgetAlwaysSucceeds() {
        PrDebtReport r = report(THREE, PrSettings.DEFAULT);

        assertTrue(r.withinBudget());
        assertEquals("success", r.statusState());
        assertEquals("3 new debt markers", r.statusDescription());
    }

    @Test
    void totalAndPerLabelBudgets() {
        PrDebtReport r = report(THREE, new PrSettings(true, true, 2, Map.of("TODO", 0, "FIXME", 1, "HACK", 0)));

        assertEquals(List.of("total 3/2", "FIXME 2/1", "TODO 1/0"), r.violations());
        assertEquals("failure", r.statusState());
        assertEquals("3 new debt markers, over budget: total 3/2, FIXME 2/1, TODO 1/0", r.statusDescription());

        PrDebtReport ok = report(THREE.subList(0, 1), new PrSettings(true, true, 2, Map.of("FIXME", 1)));
        assertEquals("1 new debt marker, within budget", ok.statusDescription());
    }

    @Test
    void commentHasMarkerPermalinksAndCleanState() {
        String body = report(THREE, new PrSettings(true, true, 0, Map.of())).commentBody("https://hm/dashboard/repos/5");

        assertTrue(body.startsWith(PrDebtReport.MARKER));
        assertTrue(body.contains("❌"));
        assertTrue(body.contains("https://github.com/o/r/blob/" + SHA + "/src/a.ts#L9"), body);
        assertTrue(body.contains("[Open in Hashmark](https://hm/dashboard/repos/5)"));
        assertTrue(body.contains("Commit 0123456"));

        String clean = report(List.of(), PrSettings.DEFAULT).commentBody(null);
        assertTrue(clean.contains("No new debt markers"));
        assertFalse(clean.contains("Open in Hashmark"));
    }

    @Test
    void commentNeutralizesMarkupMentionsAndReferences() {
        String hostile = "<img src=x onerror=alert(1)> | **bold** [x](http://evil) @octocat fixes #12";
        String body = report(List.of(new NewDebt("TODO", "a.ts", 1, hostile)), PrSettings.DEFAULT).commentBody(null);

        assertFalse(body.contains("<img"), body);
        assertFalse(body.contains("@octocat"), "etiketleme bildirimi üretmemeli");
        assertFalse(body.contains("#12 "), "issue referansı bağlantı olmamalı");
        assertFalse(body.contains("[x](http://evil)"));
        assertTrue(body.contains("\\|"), "tablo hücresinden taşmamalı");
    }

    @Test
    void longListsAreCapped() {
        List<NewDebt> many = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            many.add(new NewDebt("TODO", "a.ts", i, "x".repeat(300)));
        }
        String body = report(many, PrSettings.DEFAULT).commentBody(null);

        assertEquals(PrDebtReport.MAX_ROWS, body.lines().filter(l -> l.startsWith("| `TODO`")).count());
        assertTrue(body.contains("…and 10 more."));
        assertFalse(body.contains("x".repeat(PrDebtReport.MAX_TEXT + 1)));
    }
}
