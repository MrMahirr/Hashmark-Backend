package dev.hashmark.scanner.service;

import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.scanner.config.RepoScanConfig;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebtParserServiceTest {

    private final DebtParserService parser = new DebtParserService();

    private void assertDebt(DebtDto dto, int line, String label, String content) {
        assertEquals(line, dto.getLineNo(), "satır no");
        assertEquals(label, dto.getLabel(), "etiket");
        assertEquals(content, dto.getContent(), "içerik");
    }

    @Test
    void parsesWholeLineCommentsOfEveryKind() {
        String input = """
                // TODO: implement this
                # FIXME: broken logic
                /* HACK: temporary workaround */
                // normal comment
                -- XXX: database issue
                /** @param id user id */
                // config: max timeout 30s
                """;

        List<DebtDto> debts = parser.parse(input, "test.txt");

        assertEquals(7, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "implement this");
        assertDebt(debts.get(1), 2, "FIXME", "broken logic");
        assertDebt(debts.get(2), 3, "HACK", "temporary workaround");
        assertDebt(debts.get(3), 4, "NOTE", "normal comment");
        assertDebt(debts.get(4), 5, "XXX", "database issue");
        assertDebt(debts.get(5), 6, "DOC", "@param id user id");
        assertDebt(debts.get(6), 7, "INFO", "config: max timeout 30s");
    }

    @Test
    void detectsInlineTrailingComments() {
        String input = """
                int retries = 3; // TODO: make configurable
                call(); /* FIXME: handle null */
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(2, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "make configurable");
        assertDebt(debts.get(1), 2, "FIXME", "handle null");
    }

    @Test
    void ignoresCommentMarkersInsideStrings() {
        String input = """
                String url = "http://example.com // TODO: not a comment";
                String s = "/* FIXME: nope */";
                char c = '"'; // TODO: real one
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 3, "TODO", "real one");
    }

    @Test
    void ignoresMarkersInsideMultilineStrings() {
        String input = """
                String s = \"\"\"
                    // TODO: inside text block
                    \"\"\";
                // TODO: after
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 4, "TODO", "after");
    }

    @Test
    void doesNotTreatPreprocessorOrAttributesAsComments() {
        String c = """
                #include <stdio.h>
                #define MAX 10
                int x; // TODO: remove
                """;
        List<DebtDto> debts = parser.parse(c, "main.c");
        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 3, "TODO", "remove");

        String rust = """
                #[derive(Debug)]
                fn f<'a>(x: &'a str) {} // FIXME: lifetimes
                """;
        debts = parser.parse(rust, "lib.rs");
        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 2, "FIXME", "lifetimes");
    }

    @Test
    void doesNotTreatRegexSlashesAsComments() {
        String input = "const r = s.replace(/https?:\\/\\//, \"\"); // TODO: tighten\n";

        List<DebtDto> debts = parser.parse(input, "a.js");

        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "tighten");
    }

    @Test
    void joinsContinuationLinesOfADebt() {
        String input = """
                // TODO: refactor this method
                // because it mixes parsing
                // and persistence
                int x;
                // plain note
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(2, debts.size());
        assertDebt(debts.get(0), 1, "TODO",
                "refactor this method because it mixes parsing and persistence");
        assertDebt(debts.get(1), 5, "NOTE", "plain note");
    }

    @Test
    void continuationStopsAtNextLabelAndDocTags() {
        String input = """
                /*
                 * TODO: first
                 * more detail
                 * FIXME: second
                 * @param x value
                 */
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(3, debts.size());
        assertDebt(debts.get(0), 2, "TODO", "first more detail");
        assertDebt(debts.get(1), 4, "FIXME", "second");
        assertDebt(debts.get(2), 5, "DOC", "@param x value");
    }

    @Test
    void extractsAssigneePriorityDateAndIssue() {
        String input = """
                // TODO(mahir): fix cache invalidation
                // FIXME[P1]: crash on empty repo
                // TODO(high, #123): rate limit
                // TODO: review by 2026-12-01 see JIRA-42
                // TODO: @ayse update docs
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(5, debts.size());
        assertEquals("mahir", debts.get(0).getAssignee());
        assertEquals("fix cache invalidation", debts.get(0).getContent());

        assertEquals("HIGH", debts.get(1).getPriority());
        assertEquals("crash on empty repo", debts.get(1).getContent());

        assertEquals("HIGH", debts.get(2).getPriority());
        assertEquals("#123", debts.get(2).getIssueRef());

        assertEquals(LocalDate.of(2026, 12, 1), debts.get(3).getDueDate());
        assertEquals("JIRA-42", debts.get(3).getIssueRef());

        assertEquals("ayse", debts.get(4).getAssignee());
    }

    @Test
    void noMetadataWhenAbsent() {
        DebtDto dto = parser.parse("// TODO: plain\n", "A.java").get(0);

        assertNull(dto.getAssignee());
        assertNull(dto.getPriority());
        assertNull(dto.getDueDate());
        assertNull(dto.getIssueRef());
    }

    @Test
    void doesNotMistakeProseForLabels() {
        String input = """
                // Note that this is slow
                // info about the cache
                // hacky but works
                // The report is generated nightly
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(4, debts.size());
        assertTrue(debts.stream().allMatch(d -> d.getLabel().equals("NOTE") || d.getLabel().equals("INFO")),
                "düz cümleler borç etiketi almamalı: " + debts);
        // "report" içindeki "port" artık INFO tetiklemez
        assertEquals("NOTE", debts.get(3).getLabel());
    }

    @Test
    void acceptsLowercaseTodoAndAtTodoTags() {
        String input = """
                // todo: lower case
                /** @todo phpdoc style */
                """;

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(2, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "lower case");
        assertDebt(debts.get(1), 2, "TODO", "phpdoc style");
    }

    @Test
    void skipsSeparatorsDirectivesAndEmptyComments() {
        String input = """
                // ----------------------------
                /*********/
                //
                // eslint-disable-next-line no-console
                //go:build linux
                // @ts-ignore
                // region helpers
                // x
                """;

        assertTrue(parser.parse(input, "a.ts").isEmpty());
    }

    @Test
    void parsesPythonCommentsAndDocstrings() {
        String input = """
                #!/usr/bin/env python
                # -*- coding: utf-8 -*-
                def f(x):
                    \"\"\"Compute value.

                    TODO: support negatives
                    \"\"\"
                    s = "# not a comment"
                    return x  # FIXME: wrong for zero
                """;

        List<DebtDto> debts = parser.parse(input, "m.py");

        assertEquals(3, debts.size());
        assertDebt(debts.get(0), 4, "DOC", "Compute value.");
        assertDebt(debts.get(1), 6, "TODO", "support negatives");
        assertDebt(debts.get(2), 9, "FIXME", "wrong for zero");
    }

    @Test
    void parsesRubyBlocksAndShellHashRules() {
        String ruby = "=begin\nTODO: block todo\n=end\nx = 1 # FIXME: inline\n";
        List<DebtDto> debts = parser.parse(ruby, "a.rb");
        assertEquals(2, debts.size());
        assertDebt(debts.get(0), 2, "TODO", "block todo");
        assertDebt(debts.get(1), 4, "FIXME", "inline");

        String sh = "echo ${#arr[@]} # TODO: count\nx=a#b\n";
        debts = parser.parse(sh, "run.sh");
        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "count");
    }

    @Test
    void parsesHtmlAndSqlComments() {
        List<DebtDto> debts = parser.parse("<div></div> <!-- TODO: add aria -->\n", "i.html");
        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "add aria");

        debts = parser.parse("SELECT 1; -- FIXME: slow\n", "q.sql");
        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 1, "FIXME", "slow");
    }

    @Test
    void oneEntryPerLinePreferringDebtLabels() {
        String input = "/* config value */ run(); // TODO: tune\n";

        List<DebtDto> debts = parser.parse(input, "A.java");

        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "tune");
    }

    @Test
    void handlesCrlfBomAndEmptyInput() {
        assertTrue(parser.parse(null, "a.java").isEmpty());
        assertTrue(parser.parse("", "a.java").isEmpty());

        List<DebtDto> debts = parser.parse("﻿// TODO: a\r\n// FIXME: b\r\n", "A.java");
        assertEquals(2, debts.size());
        assertDebt(debts.get(0), 1, "TODO", "a");
        assertDebt(debts.get(1), 2, "FIXME", "b");
    }

    @Test
    void clipsVeryLongContent() {
        String input = "// TODO: " + "x".repeat(2000) + "\n";

        DebtDto dto = parser.parse(input, "A.java").get(0);

        assertEquals(500, dto.getContent().length());
    }

    @Test
    void classifiesLicenseHeaderAsInfo() {
        List<DebtDto> debts = parser.parse("/*\n * Copyright 2026 Hashmark\n */\n", "A.java");

        assertEquals(1, debts.size());
        assertEquals("INFO", debts.get(0).getLabel());
    }

    private static LabelRules rules(String yaml) {
        return LabelRules.of(RepoScanConfig.parse(yaml));
    }

    @Test
    void recognizesCustomLabelsAsDebtWithMetadata() {
        LabelRules rules = rules("labels:\n  custom: [SECURITY, optimize]\n");
        String input = """
                // SECURITY(mahir, P1): validate redirect target
                // OPTIMIZE: cache this #42
                a();
                // security review happens later
                b();
                // Optimize nothing here
                """;

        List<DebtDto> debts = parser.parse(input, "A.java", rules);

        assertEquals(4, debts.size());
        assertDebt(debts.get(0), 1, "SECURITY", "validate redirect target");
        assertEquals("mahir", debts.get(0).getAssignee());
        assertEquals("HIGH", debts.get(0).getPriority());
        assertDebt(debts.get(1), 2, "OPTIMIZE", "cache this #42");
        assertEquals("#42", debts.get(1).getIssueRef());
        // küçük harfli, iki nokta/etiket içermeyen cümle etiket sayılmaz
        assertEquals("NOTE", debts.get(2).getLabel());
        assertEquals("NOTE", debts.get(3).getLabel());
    }

    @Test
    void customLabelsAreUnknownWithoutConfig() {
        List<DebtDto> debts = parser.parse("// SECURITY: check\n", "A.java");

        assertEquals("NOTE", debts.get(0).getLabel());
    }

    @Test
    void disabledLabelsAreNotRecorded() {
        LabelRules rules = rules("labels:\n  disable: [NOTE, doc, XXX]\n");
        String input = """
                /** Javadoc */
                // just a note
                // XXX: odd
                // TODO: keep
                a();
                // config url here
                """;

        List<DebtDto> debts = parser.parse(input, "A.java", rules);

        assertEquals(2, debts.size());
        assertDebt(debts.get(0), 4, "TODO", "keep");
        assertEquals("INFO", debts.get(1).getLabel());
    }

    @Test
    void customDebtLabelWinsLineOverPlainComment() {
        LabelRules rules = rules("labels:\n  custom: [PERF]\n");

        List<DebtDto> debts = parser.parse("x(); /* note */ // PERF: n^2 loop\n", "a.ts", rules);

        assertEquals(1, debts.size());
        assertDebt(debts.get(0), 1, "PERF", "n^2 loop");
    }
}
