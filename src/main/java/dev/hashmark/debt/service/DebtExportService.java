package dev.hashmark.debt.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.debt.dto.DebtDto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

/** Borç listesini CSV, JSON veya SARIF 2.1.0 olarak üretir. */
@Service
public class DebtExportService {

    public record ExportFile(String contentType, String filename, byte[] content) {
    }

    private static final List<String> CSV_HEADER = List.of(
            "id", "repo", "file", "line", "label", "status", "priority", "assignee", "dueDate", "issueRef",
            "author", "introducedAt", "detectedAt", "resolvedAt", "permalink", "content");

    private static final Map<String, String> SARIF_LEVELS = Map.of(
            "TODO", "note", "FIXME", "warning", "HACK", "warning", "XXX", "warning",
            "NOTE", "none", "DOC", "none", "INFO", "none");

    private static final Map<String, String> RULE_TEXT = Map.of(
            "TODO", "Planned work left in a code comment",
            "FIXME", "Known defect marked in a code comment",
            "HACK", "Workaround marked in a code comment",
            "XXX", "Attention-needed marker in a code comment",
            "NOTE", "General note in a code comment",
            "DOC", "Documentation comment",
            "INFO", "Informational comment");

    // Jackson 3: java.time desteği yerleşik; tarihler ISO-8601 metin olarak yazılır
    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public ExportFile export(List<DebtDto> debts, String format) {
        String normalized = format == null ? "csv" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "csv" -> new ExportFile("text/csv; charset=UTF-8", "hashmark-debts.csv", csv(debts));
            case "json" -> new ExportFile("application/json", "hashmark-debts.json", json(debts));
            case "sarif" -> new ExportFile("application/sarif+json", "hashmark-debts.sarif", sarif(debts));
            default -> throw ApiException.badRequest("Unsupported export format: " + format);
        };
    }

    // ---------------- CSV ----------------

    byte[] csv(List<DebtDto> debts) {
        StringBuilder out = new StringBuilder("﻿"); // Excel UTF-8 algılasın
        out.append(String.join(",", CSV_HEADER)).append("\r\n");
        for (DebtDto d : debts) {
            String[] cells = {
                    str(d.getId()), d.getRepoFullName(), d.getFilePath(), str(d.getLineNo()), d.getLabel(),
                    status(d), d.getPriority(), d.getAssignee(), str(d.getDueDate()), d.getIssueRef(),
                    d.getAuthorLogin() != null ? d.getAuthorLogin() : d.getAuthorName(),
                    str(d.getIntroducedAt()), str(d.getDetectedAt()), str(d.getResolvedAt()),
                    d.getPermalink(), d.getContent()
            };
            for (int i = 0; i < cells.length; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(csvCell(cells[i]));
            }
            out.append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Tırnaklar çiftlenir; Excel formülü olarak yorumlanabilecek hücreler ' ile başlatılır (CSV injection). */
    static String csvCell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty()) {
            char first = v.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') {
                v = "'" + v;
            }
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String status(DebtDto d) {
        if (d.getResolvedAt() != null) {
            return "resolved";
        }
        return d.getTriageStatus() == null || "OPEN".equals(d.getTriageStatus())
                ? "open" : d.getTriageStatus().toLowerCase(Locale.ROOT);
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    // ---------------- JSON ----------------

    byte[] json(List<DebtDto> debts) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(debts);
        } catch (Exception e) {
            throw ApiException.badGateway("Could not serialize export");
        }
    }

    // ---------------- SARIF ----------------

    byte[] sarif(List<DebtDto> debts) {
        ObjectNode root = mapper.createObjectNode();
        root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        root.put("version", "2.1.0");

        ObjectNode driver = mapper.createObjectNode();
        driver.put("name", "Hashmark");
        driver.put("informationUri", "https://hashmark.yunusemremahir.com.tr");
        ArrayNode rules = driver.putArray("rules");
        for (Map.Entry<String, String> rule : new java.util.TreeMap<>(RULE_TEXT).entrySet()) {
            ObjectNode node = rules.addObject();
            node.put("id", rule.getKey());
            node.putObject("shortDescription").put("text", rule.getValue());
        }

        ArrayNode results = mapper.createArrayNode();
        for (DebtDto d : debts) {
            if (d.getResolvedAt() != null) {
                continue; // SARIF yalnızca güncel bulguları taşır
            }
            ObjectNode result = results.addObject();
            String label = d.getLabel() == null ? "NOTE" : d.getLabel();
            result.put("ruleId", label);
            result.put("level", SARIF_LEVELS.getOrDefault(label, "none"));
            result.putObject("message").put("text", d.getContent() == null ? "" : d.getContent());

            ObjectNode physical = result.putArray("locations").addObject().putObject("physicalLocation");
            physical.putObject("artifactLocation").put("uri", d.getFilePath());
            physical.putObject("region").put("startLine", d.getLineNo() == null ? 1 : Math.max(1, d.getLineNo()));

            if (d.getFingerprint() != null) {
                result.putObject("partialFingerprints").put("hashmarkFingerprint/v1", d.getFingerprint());
            }
            ObjectNode props = result.putObject("properties");
            if (d.getRepoFullName() != null) props.put("repository", d.getRepoFullName());
            if (d.getAssignee() != null) props.put("assignee", d.getAssignee());
            if (d.getPriority() != null) props.put("priority", d.getPriority());
            LocalDate due = d.getDueDate();
            if (due != null) props.put("dueDate", due.toString());
            if (d.getPermalink() != null) props.put("permalink", d.getPermalink());
        }

        ObjectNode run = mapper.createObjectNode();
        run.putObject("tool").set("driver", driver);
        run.set("results", results);
        root.putArray("runs").add(run);

        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        } catch (Exception e) {
            throw ApiException.badGateway("Could not serialize export");
        }
    }
}
