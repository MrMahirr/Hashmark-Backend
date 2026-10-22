package dev.hashmark.settings.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.settings.dto.UserSettingsDto;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void hourlyRateIsRoundedAndZeroClearsIt() {
        assertEquals(new BigDecimal("12.35"), UserSettingsService.normalizeHourlyRate(new BigDecimal("12.345")));
        assertNull(UserSettingsService.normalizeHourlyRate(BigDecimal.ZERO));
        assertThrows(ApiException.class, () -> UserSettingsService.normalizeHourlyRate(new BigDecimal("-1")));
        assertThrows(ApiException.class, () -> UserSettingsService.normalizeHourlyRate(new BigDecimal("100001")));
    }

    @Test
    void currencyMustBeSupported() {
        assertEquals("TRY", UserSettingsService.normalizeCurrency(" try "));
        assertThrows(ApiException.class, () -> UserSettingsService.normalizeCurrency("BTC"));
    }

    @Test
    void rateWithoutCurrencyDefaultsToUsd() {
        UserSettingsRepository repository = mock(UserSettingsRepository.class);
        when(repository.findByUserId(1L)).thenReturn(Optional.of(UserSettings.builder().userId(1L).build()));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        UserSettings saved = new UserSettingsService(repository)
                .updateSettings(1L, UserSettingsDto.builder().hourlyRate(new BigDecimal("40")).build());

        assertEquals(new BigDecimal("40.00"), saved.getHourlyRate());
        assertEquals("USD", saved.getCurrency());
    }
}
