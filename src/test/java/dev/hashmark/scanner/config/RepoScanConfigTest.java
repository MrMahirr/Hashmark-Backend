package dev.hashmark.scanner.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoScanConfigTest {

    @Test
    void missingOrEmptyFileIsDefault() {
        assertSame(RepoScanConfig.DEFAULT, RepoScanConfig.parse(null));
        assertSame(RepoScanConfig.DEFAULT, RepoScanConfig.parse("   \n"));
        assertSame(RepoScanConfig.DEFAULT, RepoScanConfig.parse("# only a comment\n"));
    }

    @Test
    void parsesFullConfig() {
        RepoScanConfig config = RepoScanConfig.parse("""
                version: 1
                include:
                  - src/
                  - "scripts/*.sh"
                exclude: docs/
                labels:
                  custom: [security, OPTIMIZE, Perf_2]
                  disable:
                    - note
                    - SECURITY
                """);

        assertEquals(List.of("src/", "scripts/*.sh"), config.include());
        assertEquals(List.of("docs/"), config.exclude());
        assertEquals(List.of("SECURITY", "OPTIMIZE", "PERF_2"), config.customLabels());
        assertEquals(Set.of("NOTE", "SECURITY"), config.disabledLabels());
        assertTrue(config.warnings().isEmpty(), config.warnings().toString());
        assertFalse(config.isEmpty());
    }

    @Test
    void invalidYamlFallsBackToDefaultWithLineOnlyWarning() {
        RepoScanConfig config = RepoScanConfig.parse("include:\n  - a\n secret-token: [unclosed\n");

        assertTrue(config.isEmpty());
        assertEquals(1, config.warnings().size());
        String warning = config.warnings().get(0);
        assertTrue(warning.startsWith("invalid YAML at line"), warning);
        assertFalse(warning.contains("secret-token"), "dosya içeriği mesaja sızmamalı");
    }

    @Test
    void rejectsNonMappingRoot() {
        RepoScanConfig config = RepoScanConfig.parse("- a\n- b\n");

        assertTrue(config.isEmpty());
        assertTrue(config.warnings().get(0).contains("mapping"));
    }

    @Test
    void reportsUnknownKeysAndBadValuesButKeepsValidParts() {
        RepoScanConfig config = RepoScanConfig.parse("""
                version: 2
                excludes: [x]
                include: {a: b}
                exclude: ["!keep.js", "ok/", 42]
                labels:
                  custom: [TODO, A, TOOLONGLABEL1, "bad-name", OK_1, OK_1]
                  disable: [NOPE]
                  colors: {}
                """);

        assertEquals(List.of(), config.include());
        assertEquals(List.of("ok/", "42"), config.exclude());
        assertEquals(List.of("OK_1"), config.customLabels());
        assertTrue(config.disabledLabels().isEmpty());
        String all = String.join(" | ", config.warnings());
        assertTrue(all.contains("unknown key 'excludes'"), all);
        assertTrue(all.contains("unsupported version"), all);
        assertTrue(all.contains("'include' must be a list"), all);
        assertTrue(all.contains("negation"), all);
        assertTrue(all.contains("TODO is built in"), all);
        assertTrue(all.contains("'A' is invalid"), all);
        assertTrue(all.contains("'TOOLONGLABEL1' is invalid"), all);
        assertTrue(all.contains("unknown label 'NOPE'"), all);
        assertTrue(all.contains("labels.colors"), all);
    }

    @Test
    void limitsCustomLabelsAndPatterns() {
        StringBuilder yaml = new StringBuilder("labels:\n  custom: [");
        for (int i = 0; i < 15; i++) {
            yaml.append(i == 0 ? "" : ", ").append("L").append(i).append('X');
        }
        yaml.append("]\nexclude:\n");
        for (int i = 0; i < 120; i++) {
            yaml.append("  - dir").append(i).append("/\n");
        }

        RepoScanConfig config = RepoScanConfig.parse(yaml.toString());

        assertEquals(RepoScanConfig.MAX_CUSTOM_LABELS, config.customLabels().size());
        assertEquals(RepoScanConfig.MAX_PATTERNS, config.exclude().size());
    }

    @Test
    void refusesAliasBombsAndArbitraryTypes() {
        RepoScanConfig bomb = RepoScanConfig.parse("""
                a: &a ["x","x","x","x","x","x","x","x","x"]
                b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
                c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]
                d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]
                e: [*d,*d,*d,*d,*d,*d,*d,*d,*d]
                """);
        assertTrue(bomb.isEmpty());
        assertFalse(bomb.warnings().isEmpty());

        RepoScanConfig typed = RepoScanConfig.parse("include: !!java.net.URL [\"http://example.com\"]\n");
        assertTrue(typed.isEmpty());
        assertFalse(typed.warnings().isEmpty());
    }
}
