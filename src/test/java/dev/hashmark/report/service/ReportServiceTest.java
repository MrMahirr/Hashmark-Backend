package dev.hashmark.report.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.report.dto.RepoInsightsRow;
import dev.hashmark.report.repository.ReportRepository;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.settings.model.UserSettings;
import dev.hashmark.settings.repository.UserSettingsRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private final ReportRepository reports = mock(ReportRepository.class);
    private final UserSettingsRepository settings = mock(UserSettingsRepository.class);
    private final RepoRepository repos = mock(RepoRepository.class);
    private final ReportService service = new ReportService(reports, settings, repos);

    @Test
    void comparisonListsEveryTrackedRepoWithCost() {
        when(repos.findAllByUserId(1L)).thenReturn(List.of(
                Repo.builder().id(10L).fullName("o/busy").build(),
                Repo.builder().id(11L).fullName("o/clean").build()));
        when(reports.getInsightsPerRepo(1L)).thenReturn(Map.of(
                10L, new DebtInsightsCalculator().add("FIXME", null, 0, 2, 0, 0, 0)));
        when(settings.findByUserId(1L)).thenReturn(Optional.of(
                UserSettings.builder().hourlyRate(new BigDecimal("10")).currency("EUR").build()));

        List<RepoInsightsRow> rows = service.getRepoComparison(1L);

        assertEquals(2, rows.size());
        assertEquals(2, rows.get(0).insights().getOpenDebts());
        assertEquals(new BigDecimal("80"), rows.get(0).insights().getEstimatedCost());
        assertEquals("o/clean", rows.get(1).fullName());
        assertEquals(0, rows.get(1).insights().getOpenDebts());
        assertEquals(0.0, rows.get(1).insights().getWeightedDebt());
    }

    @Test
    void hotspotsRequireOwnedRepoAndNormalizePath() {
        when(repos.findByIdAndUserId(5L, 1L)).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.getHotspots(1L, 5L, ""));
        verify(reports, never()).getHotspotRows(eq(1L), eq(5L), anyString());

        when(repos.findByIdAndUserId(6L, 1L)).thenReturn(Optional.of(Repo.builder().id(6L).build()));
        when(reports.getHotspotRows(1L, 6L, "src/api/")).thenReturn(List.of());
        assertEquals("src/api", service.getHotspots(1L, 6L, "/src/api/").path());
    }
}
