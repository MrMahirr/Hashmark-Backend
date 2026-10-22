package dev.hashmark.channel;

import dev.hashmark.report.dto.DebtInsights;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.dto.TrendDataPoint;
import dev.hashmark.report.service.HealthScore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Kanal mesaj metinleri (e-posta ile aynı {@code report.locale} dilinde). */
@Component
public class ChannelMessages {

    private final String appUrl;
    private final boolean en;

    public ChannelMessages(@Value("${app.url:http://localhost:3000}") String appUrl,
                           @Value("${report.locale:tr}") String locale) {
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        this.en = "en".equalsIgnoreCase(locale);
    }

    public ChatMessage test() {
        return new ChatMessage(en ? "Hashmark · test message" : "Hashmark · deneme mesajı",
                List.of(en ? "This channel will receive your debt summaries and alerts."
                        : "Bu kanal borç özetlerinizi ve uyarılarınızı alacak."),
                en ? "Open Hashmark" : "Hashmark'ı aç", appUrl + "/dashboard");
    }

    /** @param frequency DAILY | WEEKLY | MONTHLY */
    public ChatMessage digest(SummaryResponse summary, String frequency) {
        String period = switch (frequency == null ? "WEEKLY" : frequency.toUpperCase(Locale.ROOT)) {
            case "DAILY" -> en ? "daily" : "günlük";
            case "MONTHLY" -> en ? "monthly" : "aylık";
            default -> en ? "weekly" : "haftalık";
        };
        DebtInsights insights = summary.getInsights();
        List<String> lines = new ArrayList<>();
        int open = insights != null ? insights.getOpenDebts() : 0;
        String delta = "";
        List<TrendDataPoint> trend = summary.getTrendData();
        if (trend != null && !trend.isEmpty()) {
            TrendDataPoint last = trend.get(trend.size() - 1);
            delta = en ? " (+" + last.getNewDebts() + " new, " + last.getResolvedDebts() + " resolved this week)"
                    : " (bu hafta +" + last.getNewDebts() + " yeni, " + last.getResolvedDebts() + " çözüldü)";
        }
        lines.add((en ? open + " open debts" : open + " açık borç") + delta);
        if (insights != null) {
            HealthScore health = HealthScore.of(insights.getWeightedDebt());
            int urgent = insights.getPriorityCounts().getOrDefault("CRITICAL", 0)
                    + insights.getPriorityCounts().getOrDefault("HIGH", 0);
            lines.add(en
                    ? "Health " + health.grade() + " (" + health.score() + "/100) · 90+ days: " + insights.getStaleCount()
                      + " · urgent: " + urgent
                    : "Sağlık " + health.grade() + " (" + health.score() + "/100) · 90+ gün: " + insights.getStaleCount()
                      + " · acil: " + urgent);
            if (insights.getSecretCount() > 0) {
                lines.add(en ? "⚠ " + insights.getSecretCount() + " leaked secret(s) in comments"
                        : "⚠ Yorumlarda " + insights.getSecretCount() + " sızan sır");
            }
        }
        String labels = labelLine(summary.getLabelStats());
        if (!labels.isEmpty()) {
            lines.add(labels);
        }
        return new ChatMessage("Hashmark · " + (en ? period + " debt summary" : period + " borç özeti"), lines,
                en ? "Open dashboard" : "Paneli aç", appUrl + "/dashboard");
    }

    public ChatMessage threshold(String repoName, long repoId, int open, int threshold) {
        return new ChatMessage(en ? "Hashmark · debt threshold exceeded" : "Hashmark · borç eşiği aşıldı",
                List.of(en ? repoName + ": " + open + " open debts (threshold " + threshold + ")"
                        : repoName + ": " + open + " açık borç (eşik " + threshold + ")"),
                en ? "Open repository" : "Depoyu aç", appUrl + "/dashboard/repos/" + repoId);
    }

    public ChatMessage secrets(String repoName, long repoId, int newSecrets) {
        return new ChatMessage(en ? "Hashmark · leaked secret found" : "Hashmark · sızan sır bulundu",
                List.of(en ? repoName + ": " + newSecrets + " new comment(s) contain a secret (masked by Hashmark)."
                                : repoName + ": " + newSecrets + " yeni yorumda sır var (Hashmark maskeledi).",
                        en ? "The secret is still in the git history: revoke and rotate the key."
                                : "Sır git geçmişinde duruyor: anahtarı iptal edip yenileyin."),
                en ? "View secrets" : "Sırları gör", appUrl + "/dashboard/debts?repoId=" + repoId + "&secret=1");
    }

    private static String labelLine(LabelStats stats) {
        if (stats == null) {
            return "";
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("TODO", stats.getTodoCount());
        counts.put("FIXME", stats.getFixmeCount());
        counts.put("HACK", stats.getHackCount());
        counts.put("XXX", stats.getXxxCount());
        stats.getCustomCounts().forEach(counts::put);
        List<String> parts = new ArrayList<>();
        counts.forEach((label, n) -> {
            if (n != null && n > 0) {
                parts.add(label + " " + n);
            }
        });
        return String.join(" · ", parts);
    }
}
