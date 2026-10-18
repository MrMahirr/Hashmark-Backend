package dev.hashmark.pr;

import dev.hashmark.debt.util.PermalinkBuilder;
import dev.hashmark.scanner.config.RepoScanConfig.PrSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PR'ın eklediği borçlar, bütçe değerlendirmesi ve GitHub'a yazılacak metinler (commit durumu + PR yorumu).
 *
 * @param newDebts  yalnızca PR'da <b>eklenen</b> satırlardaki borçlar
 * @param unchecked diff'i GitHub'da görünmeyen ya da sınır nedeniyle okunmayan değişen dosya sayısı
 */
public record PrDebtReport(String repoFullName, int prNumber, String headSha, List<NewDebt> newDebts,
                           int unchecked, PrSettings settings) {

    public static final String MARKER = "<!-- hashmark:debt-report -->";
    public static final String STATUS_CONTEXT = "hashmark/debt-budget";
    static final int MAX_ROWS = 50;
    static final int MAX_TEXT = 120;

    public record NewDebt(String label, String path, int line, String content) {
    }

    public PrDebtReport {
        newDebts = List.copyOf(newDebts);
    }

    /** Etiket → yeni borç sayısı (alfabetik). */
    public Map<String, Integer> countsByLabel() {
        Map<String, Integer> counts = new TreeMap<>();
        for (NewDebt d : newDebts) {
            counts.merge(d.label(), 1, Integer::sum);
        }
        return counts;
    }

    /** Aşılan sınırlar ("FIXME 2/0" gibi); boşsa bütçe içinde. */
    public List<String> violations() {
        List<String> out = new ArrayList<>();
        if (settings.maxNew() != null && newDebts.size() > settings.maxNew()) {
            out.add("total " + newDebts.size() + "/" + settings.maxNew());
        }
        Map<String, Integer> counts = countsByLabel();
        // Alfabetik: durum açıklaması her çalıştırmada aynı olsun
        new TreeMap<>(settings.labelBudget()).forEach((label, limit) -> {
            int n = counts.getOrDefault(label, 0);
            if (n > limit) {
                out.add(label + " " + n + "/" + limit);
            }
        });
        return out;
    }

    public boolean withinBudget() {
        return violations().isEmpty();
    }

    public String statusState() {
        return withinBudget() ? "success" : "failure";
    }

    public String statusDescription() {
        String count = newDebts.size() == 1 ? "1 new debt marker" : newDebts.size() + " new debt markers";
        if (!settings.hasBudget()) {
            return count;
        }
        return withinBudget() ? count + ", within budget" : count + ", over budget: " + String.join(", ", violations());
    }

    /** PR yorumu (markdown). Kod yorumlarından gelen metin etkisizleştirilir (markdown/HTML/@etiketleme). */
    public String commentBody(String dashboardUrl) {
        StringBuilder md = new StringBuilder(MARKER).append('\n');
        md.append("### Hashmark · debt report\n\n");
        if (newDebts.isEmpty()) {
            md.append("✅ No new debt markers in this pull request.\n");
        } else {
            md.append(withinBudget() ? "" : "❌ ").append("**").append(statusDescription()).append("**\n\n");
            md.append("| Label | Location | Comment |\n|---|---|---|\n");
            for (NewDebt d : newDebts.subList(0, Math.min(MAX_ROWS, newDebts.size()))) {
                String link = PermalinkBuilder.build(repoFullName, headSha, d.path(), d.line());
                md.append("| `").append(d.label()).append("` | [")
                  .append(escape(d.path())).append(":").append(d.line()).append("](").append(link).append(") | ")
                  .append(escape(clip(d.content()))).append(" |\n");
            }
            if (newDebts.size() > MAX_ROWS) {
                md.append("\n…and ").append(newDebts.size() - MAX_ROWS).append(" more.\n");
            }
        }
        if (unchecked > 0) {
            md.append("\n<sub>").append(unchecked)
              .append(" changed file(s) could not be checked (diff too large or file limit reached).</sub>\n");
        }
        md.append("\n<sub>Commit ").append(headSha, 0, 7).append(" · rules: `.hashmark.yml`");
        if (dashboardUrl != null) {
            md.append(" · [Open in Hashmark](").append(dashboardUrl).append(")");
        }
        md.append("</sub>\n");
        return md.toString();
    }

    private static String clip(String text) {
        String oneLine = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return oneLine.length() > MAX_TEXT ? oneLine.substring(0, MAX_TEXT - 1) + "…" : oneLine;
    }

    /** Tablo hücresine güvenli metin: HTML yok, markdown etkisiz, @etiketleme ve #referans bildirim üretmez. */
    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '|', '\\', '`', '*', '_', '[', ']', '(', ')', '~', '!' -> out.append('\\').append(c);
                // sıfır genişlikli boşluk: "@kisi" ve "#12" GitHub'da bağlantı/bildirim olmaz
                case '@', '#' -> out.append(c).append('​');
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
