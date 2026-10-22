package dev.hashmark.report.scheduler;

import dev.hashmark.settings.model.UserSettings;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * Özetin bu saatte gönderilip gönderilmeyeceği, kullanıcının saat diliminde:
 * <ul>
 *   <li>DAILY: her gün; WEEKLY: {@code notifyDay} günü; MONTHLY: ayın 1'i</li>
 *   <li>yerel saat {@code digestHour}'a ulaştıysa (zamanlayıcı bir saati kaçırırsa aynı gün içinde yetişir)</li>
 *   <li>o yerel günde henüz gönderilmediyse</li>
 * </ul>
 */
public final class DigestSchedule {

    private DigestSchedule() {
    }

    /** Ayardaki saat dilimi bozuksa varsayılan kullanılır. */
    public static ZoneId zoneOf(UserSettings settings, ZoneId fallback) {
        String tz = settings.getDigestTimezone();
        if (tz == null || tz.isBlank()) {
            return fallback;
        }
        try {
            return ZoneId.of(tz);
        } catch (DateTimeException e) {
            return fallback;
        }
    }

    /** Vadesi geldiyse gönderim günü (yerel tarih), gelmediyse {@code null}. */
    public static LocalDate dueDate(UserSettings settings, Instant now, ZoneId fallbackZone) {
        ZonedDateTime local = now.atZone(zoneOf(settings, fallbackZone));
        LocalDate today = local.toLocalDate();
        LocalDate last = settings.getLastDigestSentOn();
        if (last != null && !last.isBefore(today)) {
            return null;
        }
        int hour = settings.getDigestHour() != null ? settings.getDigestHour() : 8;
        if (local.getHour() < hour) {
            return null;
        }
        String frequency = settings.getDigestFrequency() == null ? "WEEKLY"
                : settings.getDigestFrequency().toUpperCase(Locale.ROOT);
        boolean dayMatches = switch (frequency) {
            case "DAILY" -> true;
            case "MONTHLY" -> today.getDayOfMonth() == 1;
            default -> today.getDayOfWeek() == weekday(settings.getNotifyDay());
        };
        return dayMatches ? today : null;
    }

    private static DayOfWeek weekday(String name) {
        try {
            return name == null ? DayOfWeek.MONDAY : DayOfWeek.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DayOfWeek.MONDAY;
        }
    }
}
