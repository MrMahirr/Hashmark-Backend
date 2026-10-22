package dev.hashmark.report.scheduler;

import dev.hashmark.auth.model.User;
import dev.hashmark.report.service.DigestService;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import dev.hashmark.settings.repository.UserSettingsRepository.DigestRecipient;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WeeklyReportSchedulerTest {

    private final DigestService digest = mock(DigestService.class);
    private final UserSettingsRepository settings = mock(UserSettingsRepository.class);
    private final WeeklyReportScheduler scheduler = new WeeklyReportScheduler(digest, settings,
            mock(dev.hashmark.consent.ConsentService.class), "Europe/Istanbul");
    private final LocalDate friday = LocalDate.of(2026, 10, 16);
    /** Cuma 09:00 İstanbul (06:00 UTC) */
    private final Instant fridayMorning = Instant.parse("2026-10-16T06:00:00Z");

    private DigestRecipient recipient(long id, String day, String tz) {
        return new DigestRecipient(User.builder().id(id).email("u" + id + "@x.dev").build(),
                UserSettings.builder().userId(id).emailNotify(true).notifyDay(day).digestFrequency("WEEKLY")
                        .digestHour(8).digestTimezone(tz).build());
    }

    @Test
    void marksOnlySuccessfullySentDigests() {
        when(settings.findDigestCandidates(friday.plusDays(1)))
                .thenReturn(List.of(recipient(1, "FRIDAY", "Europe/Istanbul"), recipient(2, "FRIDAY", "Europe/Istanbul")));
        when(digest.send(any(), any())).thenReturn(true, false);

        scheduler.sendDue(fridayMorning);

        verify(settings).markDigestSent(1L, friday);
        verify(settings, never()).markDigestSent(2L, friday);
    }

    @Test
    void notYetDueInAnotherTimeZoneOrDay() {
        // New York'ta henüz 02:00; Pazartesi isteyen Cuma almaz
        when(settings.findDigestCandidates(friday.plusDays(1)))
                .thenReturn(List.of(recipient(1, "FRIDAY", "America/New_York"), recipient(2, "MONDAY", "Europe/Istanbul")));

        scheduler.sendDue(fridayMorning);

        verify(digest, never()).send(any(), any());
    }

    @Test
    void oneFailureDoesNotStopTheRest() {
        when(settings.findDigestCandidates(friday.plusDays(1)))
                .thenReturn(List.of(recipient(1, "FRIDAY", "Europe/Istanbul"), recipient(2, "FRIDAY", "Europe/Istanbul")));
        when(digest.send(any(), any())).thenThrow(new RuntimeException("boom")).thenReturn(true);

        scheduler.sendDue(fridayMorning);

        verify(settings).markDigestSent(2L, friday);
    }
}
