package dev.hashmark.report.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.mail.EmailSender;
import dev.hashmark.common.mail.EmailTemplateEngine;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.dto.ModuleDebtInfo;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceTest {

    private EmailSender sender;
    private ReportService reports;
    private EmailService service;
    private final User user = User.builder().id(7L).name("Ayşe <b>").email("gh@x.dev").build();

    @BeforeEach
    void setUp() {
        sender = mock(EmailSender.class);
        reports = mock(ReportService.class);
        service = new EmailService(sender, new EmailTemplateEngine(), reports, mock(UserRepository.class),
                mock(UserSettingsService.class), "https://app.example.com/", "tr");
        SummaryResponse summary = SummaryResponse.builder()
                .trendData(new ArrayList<>())
                .labelStats(LabelStats.builder().todoCount(4).fixmeCount(2).hackCount(1).noteCount(10).build())
                .topModules(List.of(new ModuleDebtInfo("src/pay<>", 5)))
                .build();
        when(reports.getSummary(7L, null)).thenReturn(summary);
        when(sender.send(anyString(), any(), anyString(), anyString())).thenReturn(true);
    }

    @Test
    void sendsToGithubEmailWithAllLabelsByDefault() {
        UserSettings settings = UserSettings.builder().userId(7L).emailNotify(true).notifyDay("MONDAY").build();

        assertTrue(service.sendWeeklyReport(user, settings));

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("gh@x.dev"), eq("Ayşe <b>"), anyString(), html.capture());
        assertTrue(html.getValue().contains(">17<"), "toplam 4+2+1+10");
        assertTrue(html.getValue().contains("https://app.example.com/dashboard"), "sondaki / kırpılmalı");
    }

    @Test
    void usesAlternativeAddressAndFiltersLabels() {
        UserSettings settings = UserSettings.builder().userId(7L).emailNotify(true).notifyDay("MONDAY")
                .digestEmail("team@x.dev").digestLabels("TODO,FIXME").build();

        service.sendWeeklyReport(user, settings);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(sender).send(eq("team@x.dev"), any(), anyString(), html.capture());
        assertTrue(html.getValue().contains(">6<"), "yalnızca TODO + FIXME");
        assertFalse(html.getValue().contains(">NOTE<"));
    }

    @Test
    void escapesUserControlledText() {
        UserSettings settings = UserSettings.builder().userId(7L).build();

        service.sendWeeklyReport(user, settings);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(sender).send(anyString(), any(), anyString(), html.capture());
        assertFalse(html.getValue().contains("<b>"), "kullanıcı adı kaçışlanmalı");
        assertTrue(html.getValue().contains("Ayşe &lt;b&gt;"));
        assertTrue(html.getValue().contains("src/pay&lt;&gt;"));
    }

    @Test
    void skipsWhenNoRecipientAddress() {
        User noMail = User.builder().id(7L).name("X").build();

        assertFalse(service.sendWeeklyReport(noMail, UserSettings.builder().userId(7L).build()));
        verify(sender, never()).send(anyString(), any(), anyString(), anyString());
    }

    @Test
    void rendersBrandedTurkishLayoutWithLabelBars() {
        UserSettings settings = UserSettings.builder().userId(7L).build();

        service.sendWeeklyReport(user, settings);

        ArgumentCaptor<String> subject = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(sender).send(anyString(), any(), subject.capture(), html.capture());
        assertEquals("Hashmark · haftalık borç özetiniz", subject.getValue());
        String body = html.getValue();
        assertTrue(body.contains("lang=\"tr\""));
        assertTrue(body.contains("hashmark</td>"), "marka bandı");
        assertTrue(body.contains("#CDF24A"), "limon vurgu");
        assertTrue(body.contains("Etikete göre"));
        assertFalse(body.contains("{{"), "çözümlenmemiş işaretçi kalmamalı");
    }

    @Test
    void labelCountsListsAllSevenLabelsInOrder() {
        assertEquals(List.of("TODO", "FIXME", "HACK", "XXX", "NOTE", "DOC", "INFO"), EmailService.allLabels());
    }
}
