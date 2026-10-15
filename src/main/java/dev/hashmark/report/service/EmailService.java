package dev.hashmark.report.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.mail.EmailSender;
import dev.hashmark.common.mail.EmailTemplateEngine;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.ModuleDebtInfo;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.dto.TrendDataPoint;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.service.UserSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Haftalık özet e-postasını oluşturur ve {@link EmailSender} (Brevo) ile gönderir. */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String TEMPLATE = "weekly-report";

    /** Etiket renkleri (açık tema; e-posta istemcileri CSS değişkenlerini desteklemez). */
    private static final Map<String, String> LABEL_COLORS = Map.of(
            "TODO", "#1C56AE", "FIXME", "#A22E0D", "HACK", "#7C5000", "XXX", "#7136BB",
            "NOTE", "#0D645D", "DOC", "#4338CA", "INFO", "#00607E");

    private final EmailSender emailSender;
    private final EmailTemplateEngine templates;
    private final ReportService reportService;
    private final UserRepository userRepository;
    private final UserSettingsService settingsService;
    private final String appUrl;
    private final Map<String, String> text;
    private final String lang;

    public EmailService(EmailSender emailSender, EmailTemplateEngine templates, ReportService reportService,
                        UserRepository userRepository, UserSettingsService settingsService,
                        @Value("${app.url:http://localhost:3000}") String appUrl,
                        @Value("${report.locale:tr}") String locale) {
        this.emailSender = emailSender;
        this.templates = templates;
        this.reportService = reportService;
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        this.lang = "en".equalsIgnoreCase(locale) ? "en" : "tr";
        this.text = "en".equals(this.lang) ? MessagesEn.VALUES : MessagesTr.VALUES;
    }

    /** E-posta gönderimi yapılandırılmış mı (Brevo API anahtarı). */
    public boolean isConfigured() {
        return emailSender.isConfigured();
    }

    /**
     * Kullanıcının özetini kendi ayarlarına (etiketler, alternatif adres) göre gönderir.
     *
     * @return gerçekten gönderildiyse {@code true}
     */
    public boolean sendWeeklyReport(User user) {
        return sendWeeklyReport(user, settingsService.getSettings(user.getId()));
    }

    public boolean sendWeeklyReport(User user, UserSettings settings) {
        String to = settings.getDigestEmail() != null && !settings.getDigestEmail().isBlank()
                ? settings.getDigestEmail()
                : user.getEmail();
        if (to == null || to.isBlank()) {
            log.info("Skipping weekly report: no email address. userId={}", user.getId());
            return false;
        }
        String html = renderReport(user, reportService.getSummary(user.getId(), null), settings);
        return emailSender.send(to, user.getName(), text.get("subject"), html);
    }

    /** Oturumdaki kullanıcıya deneme e-postası gönderir. */
    public boolean sendTestEmail(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
        return sendWeeklyReport(user);
    }

    // ------------------------------------------------------------------
    // Şablon
    // ------------------------------------------------------------------

    String renderReport(User user, SummaryResponse summary, UserSettings settings) {
        Set<String> wanted = settings.labelSet();
        Map<String, Integer> counts = labelCounts(summary.getLabelStats());
        Map<String, Integer> shown = new LinkedHashMap<>();
        counts.forEach((label, n) -> {
            if (wanted.isEmpty() || wanted.contains(label)) {
                shown.put(label, n);
            }
        });

        int total = shown.values().stream().mapToInt(Integer::intValue).sum();
        List<TrendDataPoint> trend = summary.getTrendData();
        boolean noBreakdown = counts.values().stream().mapToInt(Integer::intValue).sum() == 0;
        if (noBreakdown && trend != null && !trend.isEmpty() && wanted.isEmpty()) {
            total = trend.get(trend.size() - 1).getTotalDebts();
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("lang", lang);
        values.put("title", text.get("heading"));
        values.put("preheader", text.get("preheader").replace("{total}", String.valueOf(total)));
        values.put("eyebrow", text.get("eyebrow"));
        values.put("heading", text.get("heading"));
        values.put("greeting", text.get("greeting").replace("{name}", displayName(user)));
        values.put("totalLabel", text.get("totalLabel"));
        values.put("total", String.valueOf(total));
        values.put("delta", deltaHtml(trend));
        values.put("byLabelTitle", text.get("byLabel"));
        values.put("rows", rowsHtml(shown, total));
        values.put("topModule", topModuleHtml(summary.getTopModules()));
        values.put("appUrl", appUrl);
        values.put("cta", text.get("cta"));
        values.put("footer", text.get("footer"));
        values.put("settingsLink", text.get("settingsLink"));
        return templates.render(TEMPLATE, values);
    }

    private String displayName(User user) {
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName();
        }
        return user.getGithubLogin() != null ? user.getGithubLogin() : text.get("fallbackName");
    }

    /** Son haftanın eklenen/çözülen sayıları (kaçışlı sayı metni; güvenilir HTML). */
    private String deltaHtml(List<TrendDataPoint> trend) {
        if (trend == null || trend.isEmpty()) {
            return "";
        }
        TrendDataPoint last = trend.get(trend.size() - 1);
        return "+" + last.getNewDebts() + " " + HtmlUtils.htmlEscape(text.get("added"), "UTF-8") + "<br>"
                + last.getResolvedDebts() + " " + HtmlUtils.htmlEscape(text.get("resolved"), "UTF-8");
    }

    private String rowsHtml(Map<String, Integer> shown, int total) {
        StringBuilder rows = new StringBuilder();
        int max = shown.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        shown.forEach((label, n) -> {
            String color = LABEL_COLORS.getOrDefault(label, "#525C55");
            int width = max == 0 ? 0 : (n == 0 ? 0 : Math.max(4, Math.round(100f * n / max)));
            String cls = label.toLowerCase(java.util.Locale.ROOT);
            rows.append("<tr>")
                .append("<td width=\"72\" class=\"hm-t-").append(cls).append("\" style=\"padding:7px 0;font-family:'Courier New',Courier,monospace;font-size:13px;")
                .append("font-weight:bold;color:").append(color).append(";\">")
                .append(HtmlUtils.htmlEscape(label, "UTF-8")).append("</td>")
                .append("<td style=\"padding:7px 12px;\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" ")
                .append("cellspacing=\"0\" border=\"0\" class=\"hm-track\" style=\"background:#E1E5DC;border-radius:4px;\"><tr>")
                .append("<td class=\"hm-b-").append(cls).append("\" height=\"8\" width=\"").append(width).append("%\" style=\"height:8px;width:").append(width)
                .append("%;background:").append(color).append(";border-radius:4px;font-size:0;line-height:0;\">&nbsp;</td>")
                .append("<td style=\"font-size:0;line-height:0;\">&nbsp;</td></tr></table></td>")
                .append("<td width=\"48\" align=\"right\" class=\"hm-text\" style=\"padding:7px 0;")
                .append("font-family:'Courier New',Courier,monospace;font-size:14px;color:#111514;\">")
                .append(n).append("</td></tr>");
        });
        return rows.toString();
    }

    private String topModuleHtml(List<ModuleDebtInfo> modules) {
        if (modules == null || modules.isEmpty()) {
            return "";
        }
        ModuleDebtInfo top = modules.get(0);
        return "<tr><td class=\"hm-pad\" style=\"padding:16px 32px 0;\">"
                + "<p class=\"hm-mute\" style=\"margin:0;font-family:Arial,Helvetica,sans-serif;font-size:14px;"
                + "line-height:22px;color:#525C55;\">" + HtmlUtils.htmlEscape(text.get("topModule"), "UTF-8") + " "
                + "<code style=\"font-family:'Courier New',Courier,monospace;color:#111514;\" class=\"hm-text\">"
                + HtmlUtils.htmlEscape(top.getModulePath(), "UTF-8") + "</code> (" + top.getDebtCount() + ")</p></td></tr>";
    }

    static Map<String, Integer> labelCounts(LabelStats stats) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (stats == null) {
            return counts;
        }
        counts.put("TODO", stats.getTodoCount());
        counts.put("FIXME", stats.getFixmeCount());
        counts.put("HACK", stats.getHackCount());
        counts.put("XXX", stats.getXxxCount());
        counts.put("NOTE", stats.getNoteCount());
        counts.put("DOC", stats.getDocCount());
        counts.put("INFO", stats.getInfoCount());
        return counts;
    }

    /** Test için: kullanılabilir etiket adları. */
    static List<String> allLabels() {
        return new ArrayList<>(labelCounts(new LabelStats()).keySet());
    }

    private static final class MessagesTr {
        static final Map<String, String> VALUES = Map.ofEntries(
                Map.entry("subject", "Hashmark · haftalık borç özetiniz"),
                Map.entry("preheader", "{total} açık işaret. Bu haftanın özeti hazır."),
                Map.entry("eyebrow", "Haftalık özet"),
                Map.entry("heading", "Teknik borcun bu hafta nerede"),
                Map.entry("greeting", "Merhaba {name}, depolarınızın bu haftaki durumu aşağıda."),
                Map.entry("fallbackName", "merhaba"),
                Map.entry("totalLabel", "açık işaret"),
                Map.entry("added", "yeni"),
                Map.entry("resolved", "çözüldü"),
                Map.entry("byLabel", "Etikete göre"),
                Map.entry("topModule", "En çok borç biriken modül:"),
                Map.entry("cta", "Paneli aç"),
                Map.entry("footer", "Bu e-postayı Hashmark ayarlarınızda haftalık özet açık olduğu için alıyorsunuz."),
                Map.entry("settingsLink", "Özet ayarlarını değiştir"));
    }

    private static final class MessagesEn {
        static final Map<String, String> VALUES = Map.ofEntries(
                Map.entry("subject", "Hashmark · your weekly debt summary"),
                Map.entry("preheader", "{total} open markers. This week's summary is ready."),
                Map.entry("eyebrow", "Weekly summary"),
                Map.entry("heading", "Where your technical debt stands"),
                Map.entry("greeting", "Hi {name}, here is where your repositories stand this week."),
                Map.entry("fallbackName", "there"),
                Map.entry("totalLabel", "open markers"),
                Map.entry("added", "new"),
                Map.entry("resolved", "resolved"),
                Map.entry("byLabel", "By label"),
                Map.entry("topModule", "Module with the most debt:"),
                Map.entry("cta", "Open dashboard"),
                Map.entry("footer", "You receive this because the weekly summary is enabled in your Hashmark settings."),
                Map.entry("settingsLink", "Change summary settings"));
    }
}
