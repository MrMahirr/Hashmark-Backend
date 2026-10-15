package dev.hashmark.report.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.mail.EmailSender;
import dev.hashmark.common.mail.EmailTemplateEngine;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.ModuleDebtInfo;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.report.dto.TrendDataPoint;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.service.UserSettingsService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Örnek veriyle e-postayı {@code target/email-preview-{tr,en}.html} olarak yazar (tarayıcıda göz kontrolü için). */
class EmailPreviewTest {

    @Test
    void writesPreviewFiles() throws Exception {
        for (String locale : List.of("tr", "en")) {
            EmailService service = new EmailService(mock(EmailSender.class), new EmailTemplateEngine(),
                    mock(ReportService.class), mock(UserRepository.class), mock(UserSettingsService.class),
                    "https://hashmark.example.com", locale);
            SummaryResponse summary = SummaryResponse.builder()
                    .trendData(List.of(new TrendDataPoint()))
                    .labelStats(LabelStats.builder().todoCount(48).fixmeCount(17).hackCount(9).xxxCount(3)
                            .noteCount(120).docCount(64).infoCount(5).build())
                    .topModules(List.of(new ModuleDebtInfo("src/main/java/dev/hashmark/scanner", 61)))
                    .build();
            User user = User.builder().id(1L).name("Yunus Emre Mahir").email("mr@example.com").build();

            String html = service.renderReport(user, summary, UserSettings.builder().userId(1L).build());

            Path out = Path.of("target", "email-preview-" + locale + ".html");
            Files.createDirectories(out.getParent());
            Files.writeString(out, html, StandardCharsets.UTF_8);
            assertTrue(Files.size(out) > 3000);
        }
    }
}
