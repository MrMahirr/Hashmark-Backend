package dev.hashmark.report.scheduler;

import dev.hashmark.auth.model.User;
import dev.hashmark.report.service.EmailService;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import dev.hashmark.settings.repository.UserSettingsRepository.DigestRecipient;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportSchedulerTest {

    private final EmailService email = mock(EmailService.class);
    private final UserSettingsRepository settings = mock(UserSettingsRepository.class);
    private final WeeklyReportScheduler scheduler = new WeeklyReportScheduler(email, settings, "Europe/Istanbul");
    private final LocalDate friday = LocalDate.of(2026, 10, 16);

    private DigestRecipient recipient(long id) {
        return new DigestRecipient(User.builder().id(id).email("u" + id + "@x.dev").build(),
                UserSettings.builder().userId(id).emailNotify(true).notifyDay("FRIDAY").build());
    }

    @Test
    void marksOnlySuccessfullySentDigests() {
        when(email.isConfigured()).thenReturn(true);
        when(settings.findDigestRecipients("FRIDAY", friday)).thenReturn(List.of(recipient(1), recipient(2)));
        when(email.sendWeeklyReport(any(), any())).thenReturn(true, false);

        scheduler.sendFor(DayOfWeek.FRIDAY, friday);

        verify(settings).markDigestSent(1L, friday);
        verify(settings, never()).markDigestSent(2L, friday);
    }

    @Test
    void doesNothingWhenProviderNotConfigured() {
        when(email.isConfigured()).thenReturn(false);

        scheduler.sendFor(DayOfWeek.FRIDAY, friday);

        verify(settings, never()).findDigestRecipients(any(), any());
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        when(email.isConfigured()).thenReturn(true);
        when(settings.findDigestRecipients("FRIDAY", friday)).thenReturn(List.of(recipient(1), recipient(2)));
        when(email.sendWeeklyReport(any(), any())).thenThrow(new RuntimeException("boom")).thenReturn(true);

        scheduler.sendFor(DayOfWeek.FRIDAY, friday);

        verify(settings).markDigestSent(2L, friday);
    }
}
