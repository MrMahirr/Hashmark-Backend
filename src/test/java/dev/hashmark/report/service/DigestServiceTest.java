package dev.hashmark.report.service;

import dev.hashmark.auth.model.User;
import dev.hashmark.channel.ChannelMessages;
import dev.hashmark.channel.NotificationChannelService;
import dev.hashmark.channel.NotificationChannelService.Purpose;
import dev.hashmark.report.dto.SummaryResponse;
import dev.hashmark.settings.model.UserSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DigestServiceTest {

    private final EmailService email = mock(EmailService.class);
    private final NotificationChannelService channels = mock(NotificationChannelService.class);
    private final ReportService reports = mock(ReportService.class);
    private final DigestService service = new DigestService(email, channels,
            new ChannelMessages("https://app.example", "tr"), reports);
    private final User user = User.builder().id(1L).build();

    @Test
    void emailFailureStillSendsToChannels() {
        UserSettings settings = UserSettings.builder().emailNotify(true).digestFrequency("DAILY").build();
        when(email.isConfigured()).thenReturn(true);
        when(email.sendWeeklyReport(user, settings)).thenThrow(new RuntimeException("smtp down"));
        when(channels.hasChannels(1L, Purpose.DIGEST)).thenReturn(true);
        when(reports.getSummary(1L, null)).thenReturn(SummaryResponse.builder().build());
        when(channels.deliver(eq(1L), eq(Purpose.DIGEST), any())).thenReturn(1);

        assertTrue(service.send(user, settings));
    }

    @Test
    void emailOffAndNoChannelsSendsNothing() {
        UserSettings settings = UserSettings.builder().emailNotify(false).build();

        assertFalse(service.send(user, settings));
        verify(email, never()).sendWeeklyReport(any(), any());
        verify(reports, never()).getSummary(any(), any());
    }

    @Test
    void unconfiguredEmailProviderIsSkipped() {
        UserSettings settings = UserSettings.builder().emailNotify(true).build();
        when(email.isConfigured()).thenReturn(false);

        assertFalse(service.send(user, settings));
        verify(email, never()).sendWeeklyReport(any(), any());
    }
}
