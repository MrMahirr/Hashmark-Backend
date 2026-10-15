package dev.hashmark.settings.service;

import dev.hashmark.common.exception.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserSettingsServiceTest {

    @Test
    void normalizesDayNamesAndAliases() {
        assertEquals("MONDAY", UserSettingsService.normalizeDay("monday"));
        assertEquals("FRIDAY", UserSettingsService.normalizeDay("Fri"));
        assertThrows(ApiException.class, () -> UserSettingsService.normalizeDay("someday"));
    }

    @Test
    void labelsAreValidatedDeduplicatedAndAllMeansNull() {
        assertEquals("TODO,FIXME", UserSettingsService.normalizeLabels(List.of("todo", "FIXME", "Todo")));
        assertNull(UserSettingsService.normalizeLabels(List.of()));
        assertNull(UserSettingsService.normalizeLabels(
                List.of("TODO", "FIXME", "HACK", "XXX", "NOTE", "DOC", "INFO")));
        assertThrows(ApiException.class, () -> UserSettingsService.normalizeLabels(List.of("BUG")));
    }

    @Test
    void emailIsValidatedAndBlankClearsIt() {
        assertEquals("a@b.co", UserSettingsService.normalizeEmail("  a@b.co "));
        assertNull(UserSettingsService.normalizeEmail("   "));
        assertThrows(ApiException.class, () -> UserSettingsService.normalizeEmail("not-an-email"));
        assertThrows(ApiException.class, () -> UserSettingsService.normalizeEmail("a@b"));
    }
}
