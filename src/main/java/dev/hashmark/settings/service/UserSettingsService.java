package dev.hashmark.settings.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.settings.dto.UserSettingsDto;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class UserSettingsService {

    static final Set<String> LABELS = Set.of("TODO", "FIXME", "HACK", "XXX", "NOTE", "DOC", "INFO");
    private static final Set<String> DAYS =
            Set.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");
    private static final Map<String, String> DAY_ALIASES = Map.of(
            "MON", "MONDAY", "TUE", "TUESDAY", "WED", "WEDNESDAY", "THU", "THURSDAY",
            "FRI", "FRIDAY", "SAT", "SATURDAY", "SUN", "SUNDAY");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final int MAX_EMAIL_LENGTH = 255;
    /** Maliyet için desteklenen para birimleri (arayüzdeki seçici ile aynı). */
    public static final Set<String> CURRENCIES = Set.of("TRY", "USD", "EUR", "GBP");
    static final BigDecimal MAX_HOURLY_RATE = new BigDecimal("100000");
    static final String DEFAULT_CURRENCY = "USD";
    static final Set<String> FREQUENCIES = Set.of("DAILY", "WEEKLY", "MONTHLY");
    static final int MAX_ALERT_THRESHOLD = 1_000_000;

    private final UserSettingsRepository userSettingsRepository;

    public UserSettingsService(UserSettingsRepository userSettingsRepository) {
        this.userSettingsRepository = userSettingsRepository;
    }

    public UserSettings getSettings(Long userId) {
        Optional<UserSettings> settingsOpt = userSettingsRepository.findByUserId(userId);

        if (settingsOpt.isPresent()) {
            return settingsOpt.get();
        }

        UserSettings defaultSettings = UserSettings.builder()
                .userId(userId)
                .emailNotify(true)
                .notifyDay("MONDAY")
                .build();

        return userSettingsRepository.save(defaultSettings);
    }

    /** Kısmi güncelleme: gönderilmeyen (null) alanlar değişmez. */
    public UserSettings updateSettings(Long userId, UserSettingsDto dto) {
        UserSettings current = getSettings(userId);

        if (dto.getEmailNotify() != null) {
            current.setEmailNotify(dto.getEmailNotify());
        }
        if (dto.getNotifyDay() != null) {
            current.setNotifyDay(normalizeDay(dto.getNotifyDay()));
        }
        if (dto.getDigestLabels() != null) {
            current.setDigestLabels(normalizeLabels(dto.getDigestLabels()));
        }
        if (dto.getDigestEmail() != null) {
            current.setDigestEmail(normalizeEmail(dto.getDigestEmail()));
        }
        if (dto.getHourlyRate() != null) {
            current.setHourlyRate(normalizeHourlyRate(dto.getHourlyRate()));
        }
        if (dto.getCurrency() != null) {
            current.setCurrency(normalizeCurrency(dto.getCurrency()));
        }
        if (dto.getDigestFrequency() != null) {
            current.setDigestFrequency(normalizeFrequency(dto.getDigestFrequency()));
        }
        if (dto.getDigestHour() != null) {
            current.setDigestHour(normalizeHour(dto.getDigestHour()));
        }
        if (dto.getDigestTimezone() != null) {
            current.setDigestTimezone(normalizeTimezone(dto.getDigestTimezone()));
        }
        if (dto.getAlertMaxOpen() != null) {
            current.setAlertMaxOpen(normalizeAlertMaxOpen(dto.getAlertMaxOpen()));
        }
        if (dto.getAlertOnSecret() != null) {
            current.setAlertOnSecret(dto.getAlertOnSecret());
        }
        if (current.getHourlyRate() != null && current.getCurrency() == null) {
            current.setCurrency(DEFAULT_CURRENCY);
        }
        return userSettingsRepository.save(current);
    }

    static String normalizeDay(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        value = DAY_ALIASES.getOrDefault(value, value);
        if (!DAYS.contains(value)) {
            throw ApiException.badRequest("notifyDay must be one of " + String.join(", ", DAYS));
        }
        return value;
    }

    /** Boş liste ve "tüm etiketler" aynıdır → {@code null} (hepsi). */
    static String normalizeLabels(List<String> labels) {
        Set<String> out = new LinkedHashSet<>();
        for (String label : labels) {
            String value = label == null ? "" : label.trim().toUpperCase(Locale.ROOT);
            if (!LABELS.contains(value)) {
                throw ApiException.badRequest("Unknown label: " + label);
            }
            out.add(value);
        }
        if (out.isEmpty() || out.size() == LABELS.size()) {
            return null;
        }
        return String.join(",", out);
    }

    static String normalizeFrequency(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!FREQUENCIES.contains(value)) {
            throw ApiException.badRequest("digestFrequency must be one of DAILY, WEEKLY, MONTHLY");
        }
        return value;
    }

    static int normalizeHour(int hour) {
        if (hour < 0 || hour > 23) {
            throw ApiException.badRequest("digestHour must be between 0 and 23");
        }
        return hour;
    }

    /** Yalnızca bölge adı (Europe/Istanbul); "+03:00" gibi sabit kaymalar yaz saatini izlemez, kabul edilmez. */
    static String normalizeTimezone(String raw) {
        String value = raw.trim();
        if (value.length() > 64 || !(value.contains("/") || "UTC".equals(value)) || !ZoneId.getAvailableZoneIds().contains(value)) {
            throw ApiException.badRequest("digestTimezone must be an IANA time zone such as Europe/Istanbul");
        }
        return value;
    }

    /** 0 = uyarı kapalı ({@code null}). */
    static Integer normalizeAlertMaxOpen(int value) {
        if (value < 0 || value > MAX_ALERT_THRESHOLD) {
            throw ApiException.badRequest("alertMaxOpen must be between 0 and " + MAX_ALERT_THRESHOLD);
        }
        return value == 0 ? null : value;
    }

    /** 0 = ücret kaldırılır ({@code null}); kuruş hassasiyetine yuvarlanır. */
    static BigDecimal normalizeHourlyRate(BigDecimal raw) {
        if (raw.signum() < 0 || raw.compareTo(MAX_HOURLY_RATE) > 0) {
            throw ApiException.badRequest("hourlyRate must be between 0 and " + MAX_HOURLY_RATE.toPlainString());
        }
        if (raw.signum() == 0) {
            return null;
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    static String normalizeCurrency(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCIES.contains(value)) {
            throw ApiException.badRequest("currency must be one of " + String.join(", ", CURRENCIES));
        }
        return value;
    }

    /** Boş = GitHub e-postasını kullan ({@code null}). */
    static String normalizeEmail(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(value).matches()) {
            throw ApiException.badRequest("digestEmail is not a valid email address");
        }
        return value;
    }
}
