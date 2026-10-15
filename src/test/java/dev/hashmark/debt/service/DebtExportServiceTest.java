package dev.hashmark.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.service.DebtExportService.ExportFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebtExportServiceTest {

    private final DebtExportService service = new DebtExportService();
    private final ObjectMapper mapper = new ObjectMapper();

    private DebtDto debt(long id, String label, String content) {
        return DebtDto.builder()
                .id(id).repoFullName("o/r").filePath("src/A.java").lineNo(12).label(label).content(content)
                .triageStatus("OPEN").assignee("mahir").priority("HIGH").dueDate(LocalDate.of(2026, 12, 1))
                .detectedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5))
                .permalink("https://github.com/o/r/blob/abc/src/A.java#L12")
                .fingerprint("fp-" + id)
                .build();
    }

    @Test
    void csvHasBomHeaderAndQuotedCells() {
        ExportFile file = service.export(List.of(debt(1, "TODO", "say \"hi\", then leave")), "csv");

        String csv = new String(file.content(), StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿id,repo,file,line,label,status,"));
        assertTrue(csv.contains("\"say \"\"hi\"\", then leave\""));
        assertTrue(csv.contains("\"open\""));
        assertEquals("text/csv; charset=UTF-8", file.contentType());
    }

    @Test
    void csvNeutralizesFormulaInjection() {
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", DebtExportService.csvCell("=HYPERLINK(\"http://x\")"));
        assertEquals("\"'+1\"", DebtExportService.csvCell("+1"));
        assertEquals("\"'-1\"", DebtExportService.csvCell("-1"));
        assertEquals("\"'@SUM(A1)\"", DebtExportService.csvCell("@SUM(A1)"));
        assertEquals("\"plain\"", DebtExportService.csvCell("plain"));
        assertEquals("\"\"", DebtExportService.csvCell(null));
    }

    @Test
    void jsonRoundTripsDates() throws Exception {
        ExportFile file = service.export(List.of(debt(1, "FIXME", "x")), "json");

        JsonNode node = mapper.readTree(file.content());
        assertEquals(1, node.size());
        assertEquals("FIXME", node.get(0).get("label").asText());
        assertEquals("2026-12-01", node.get(0).get("dueDate").asText());
        assertEquals("application/json", file.contentType());
    }

    @Test
    void sarifContainsOnlyOpenFindingsWithLocations() throws Exception {
        DebtDto resolved = debt(2, "TODO", "old");
        resolved.setResolvedAt(LocalDateTime.now());

        ExportFile file = service.export(List.of(debt(1, "FIXME", "crash"), resolved, debt(3, "NOTE", "n")), "sarif");

        JsonNode root = mapper.readTree(file.content());
        assertEquals("2.1.0", root.get("version").asText());
        JsonNode results = root.get("runs").get(0).get("results");
        assertEquals(2, results.size());
        assertEquals("FIXME", results.get(0).get("ruleId").asText());
        assertEquals("warning", results.get(0).get("level").asText());
        assertEquals("none", results.get(1).get("level").asText());
        JsonNode location = results.get(0).get("locations").get(0).get("physicalLocation");
        assertEquals("src/A.java", location.get("artifactLocation").get("uri").asText());
        assertEquals(12, location.get("region").get("startLine").asInt());
        assertEquals("fp-1", results.get(0).get("partialFingerprints").get("hashmarkFingerprint/v1").asText());
        assertEquals(7, root.get("runs").get(0).get("tool").get("driver").get("rules").size());
        assertEquals("application/sarif+json", file.contentType());
    }

    @Test
    void resolvedAndIgnoredStatusesInCsv() {
        DebtDto resolved = debt(1, "TODO", "a");
        resolved.setResolvedAt(LocalDateTime.now());
        DebtDto ignored = debt(2, "TODO", "b");
        ignored.setTriageStatus("WONT_FIX");

        String csv = new String(service.export(List.of(resolved, ignored), "csv").content(), StandardCharsets.UTF_8);

        assertTrue(csv.contains("\"resolved\""));
        assertTrue(csv.contains("\"wont_fix\""));
    }

    @Test
    void rejectsUnknownFormat() {
        assertThrows(ApiException.class, () -> service.export(List.of(), "xml"));
    }

    @Test
    void defaultsToCsv() {
        assertEquals("hashmark-debts.csv", service.export(List.of(), null).filename());
    }
}
