package dev.hashmark.report.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.mail.EmailSender;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.ModuleDebtInfo;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.dto.TrendDataPoint;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.service.UserSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Haftalık özet e-postasını oluşturur ve {@link EmailSender} (Brevo) ile gönderir. */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String TEMPLATE = "templates/email/weekly-report.html";
    private static final String SUBJECT = "Hashmark - weekly debt summary";

    private final EmailSender emailSender;
    private final ReportService reportService;
    private final UserRepository userRepository;
    private final UserSettingsService settingsService;
    private final String appUrl;
    private final String template;

    public EmailService(EmailSender emailSender, ReportService reportService, UserRepository userRepository,
                        UserSettingsService settingsService, @Value("${app.url:http://localhost:3000}") String appUrl) {
        this.emailSender = emailSender;
        this.reportService = reportService;
        this.userRepository = userRepository;
        this.settingsService = settingsService;
        this.appUrl = appUrl.endsWith("/") ? appUrl.substring(0, appUrl.length() - 1) : appUrl;
        this.template = loadTemplate();
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
        UserSettings settings = settingsService.getSettings(user.getId());
        return sendWeeklyReport(user, settings);
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
        return emailSender.send(to, user.getName(), SUBJECT, html);
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
        if (counts.values().stream().mapToInt(Integer::intValue).sum() == 0) {
            // Etiket kırılımı yoksa trendin son toplamına düş
            List<TrendDataPoint> trend = summary.getTrendData();
            if (trend != null && !trend.isEmpty() && wanted.isEmpty()) {
                total = trend.get(trend.size() - 1).getTotalDebts();
            }
        }

        StringBuilder rows = new StringBuilder();
        shown.forEach((label, n) -> rows.append("<tr><td style=\"padding:6px 0;font-family:'Courier New',monospace;font-weight:700;font-size:13px;\">")
                .append(HtmlUtils.htmlEscape(label))
                .append("</td><td align=\"right\" style=\"padding:6px 0;font-family:'Courier New',monospace;font-size:14px;\">")
                .append(n)
                .append("</td></tr>"));

        String topModule = "";
        List<ModuleDebtInfo> modules = summary.getTopModules();
        if (modules != null && !modules.isEmpty()) {
            ModuleDebtInfo top = modules.get(0);
            topModule = "<p style=\"margin:0;font-size:14px;color:#566159;\">Worst module: <code>"
                    + HtmlUtils.htmlEscape(top.getModulePath()) + "</code> (" + top.getDebtCount() + ")</p>";
        }

        String name = user.getName() != null && !user.getName().isBlank() ? user.getName() : user.getGithubLogin();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("lang", "en");
        values.put("title", "Your weekly debt summary");
        values.put("greeting", "Hi " + HtmlUtils.htmlEscape(name == null ? "there" : name)
                + ", here is where your repositories stand.");
        values.put("total", String.valueOf(total));
        values.put("totalLabel", "open markers");
        values.put("rows", rows.toString());
        values.put("topModule", topModule);
        values.put("appUrl", HtmlUtils.htmlEscape(appUrl));
        values.put("cta", "Open dashboard");
        values.put("footer", "You receive this because weekly summaries are enabled in your Hashmark settings.");

        String html = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            html = html.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return html;
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

    private static String loadTemplate() {
        try (var in = new ClassPathResource(TEMPLATE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Could not load email template {}", TEMPLATE, e);
            return "<html><body><h1>{{title}}</h1><p>{{greeting}}</p><p>{{total}} {{totalLabel}}</p>"
                    + "<table>{{rows}}</table>{{topModule}}</body></html>";
        }
    }

    /** Test için: kullanılabilir etiket adları. */
    static List<String> allLabels() {
        return new ArrayList<>(labelCounts(new LabelStats()).keySet());
    }
}
