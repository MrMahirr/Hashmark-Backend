package dev.hashmark.report.scheduler;

import dev.hashmark.settings.model.UserSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DigestScheduleTest {

    private static final ZoneId IST = ZoneId.of("Europe/Istanbul");

    private static UserSettings settings(String frequency, String day, int hour, String tz, LocalDate lastSent) {
        return UserSettings.builder().digestFrequency(frequency).notifyDay(day).digestHour(hour).digestTimezone(tz)
                .lastDigestSentOn(lastSent).build();
    }

    @Test
    void weeklyOnTheDayFromTheHourOnwardOncePerDay() {
        UserSettings s = settings("WEEKLY", "FRIDAY", 9, "Europe/Istanbul", null);
        // Cuma 08:59 İstanbul → henüz değil; 09:00 ve sonrası → bugün
        assertNull(DigestSchedule.dueDate(s, Instant.parse("2026-10-16T05:59:00Z"), IST));
        assertEquals(LocalDate.of(2026, 10, 16), DigestSchedule.dueDate(s, Instant.parse("2026-10-16T06:00:00Z"), IST));
        assertEquals(LocalDate.of(2026, 10, 16), DigestSchedule.dueDate(s, Instant.parse("2026-10-16T20:00:00Z"), IST));
        // Cumartesi değil
        assertNull(DigestSchedule.dueDate(s, Instant.parse("2026-10-17T10:00:00Z"), IST));

        s.setLastDigestSentOn(LocalDate.of(2026, 10, 16));
        assertNull(DigestSchedule.dueDate(s, Instant.parse("2026-10-16T12:00:00Z"), IST));
    }

    @Test
    void usesTheUsersLocalDate() {
        // UTC Cuma 23:30 = Tokyo Cumartesi 08:30
        UserSettings s = settings("DAILY", "MONDAY", 8, "Asia/Tokyo", LocalDate.of(2026, 10, 16));
        assertEquals(LocalDate.of(2026, 10, 17),
                DigestSchedule.dueDate(s, Instant.parse("2026-10-16T23:30:00Z"), IST));
    }

    @Test
    void monthlyOnTheFirstAndFallbacks() {
        UserSettings monthly = settings("MONTHLY", null, 8, "Europe/Istanbul", null);
        assertEquals(LocalDate.of(2026, 11, 1), DigestSchedule.dueDate(monthly, Instant.parse("2026-11-01T06:00:00Z"), IST));
        assertNull(DigestSchedule.dueDate(monthly, Instant.parse("2026-11-02T06:00:00Z"), IST));

        // Bozuk saat dilimi → varsayılan; eksik alanlar → haftalık 08:00 Pazartesi
        UserSettings legacy = settings(null, null, 8, "Mars/Olympus", null);
        legacy.setDigestHour(null);
        assertEquals(LocalDate.of(2026, 10, 19), DigestSchedule.dueDate(legacy, Instant.parse("2026-10-19T05:00:00Z"), IST));
        assertNull(DigestSchedule.dueDate(legacy, Instant.parse("2026-10-19T04:59:00Z"), IST));
    }
}
