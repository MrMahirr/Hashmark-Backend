package dev.hashmark.badge;

import dev.hashmark.badge.BadgeService.BadgeData;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.report.repository.ReportRepository;
import dev.hashmark.report.service.DebtInsightsCalculator;
import dev.hashmark.report.service.HealthScore;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BadgeTest {

    private final RepoRepository repos = mock(RepoRepository.class);
    private final ReportRepository reports = mock(ReportRepository.class);
    private final ConsentService consent = mock(ConsentService.class);
    private final BadgeService service = new BadgeService(repos, reports, consent);
    private final String token = "a".repeat(43);

    @Test
    void healthScoreMatchesTheFrontendFormula() {
        assertEquals(new HealthScore(100, "A"), HealthScore.of(0));
        assertEquals(new HealthScore(85, "A"), HealthScore.of(10));
        assertEquals(new HealthScore(84, "B"), HealthScore.of(11));
        assertEquals(new HealthScore(54, "C"), HealthScore.of(31));
        assertEquals(new HealthScore(24, "D"), HealthScore.of(51));
        assertEquals(new HealthScore(0, "F"), HealthScore.of(81));
        assertEquals(new HealthScore(100, "A"), HealthScore.of(Double.NaN));
    }

    @Test
    void publicBadgeShowsGradeOfTheScannedRepo() {
        Repo repo = Repo.builder().id(5L).userId(9L).lastScannedAt(LocalDateTime.now()).build();
        when(repos.findByBadgeToken(token)).thenReturn(Optional.of(repo));
        when(reports.getInsights(9L, 5L)).thenReturn(new DebtInsightsCalculator().add("FIXME", null, 0, 8, 0, 0, 0));

        BadgeData data = service.publicBadge(token).orElseThrow();

        // 8 FIXME × 2 = 16 ağırlıklı → B, 76
        assertEquals("B · 76", data.message());
        assertEquals("#97ca00", data.color());
    }

    @Test
    void unknownMalformedUnscannedOrWithdrawnConsent() {
        assertTrue(service.publicBadge("short").isEmpty());
        assertTrue(service.publicBadge("../../etc/passwd" + "x".repeat(30)).isEmpty());
        verify(repos, never()).findByBadgeToken(anyString());

        when(repos.findByBadgeToken(token)).thenReturn(Optional.empty());
        assertTrue(service.publicBadge(token).isEmpty());

        when(repos.findByBadgeToken(token)).thenReturn(Optional.of(Repo.builder().id(5L).userId(9L).build()));
        BadgeData unscanned = service.publicBadge(token).orElseThrow();
        assertFalse(unscanned.scanned());
        assertEquals("not scanned", unscanned.message());

        when(consent.isProcessingBlocked(9L)).thenReturn(true);
        assertTrue(service.publicBadge(token).isEmpty());
    }

    @Test
    void rotateProducesUrlSafeTokens() {
        when(repos.findByIdAndUserId(5L, 9L)).thenReturn(Optional.of(Repo.builder().id(5L).tracked(true).build()));
        String a = service.rotate(9L, 5L);
        String b = service.rotate(9L, 5L);
        assertTrue(BadgeService.TOKEN.matcher(a).matches(), a);
        assertFalse(a.equals(b));
    }

    @Test
    void svgEscapesTextAndRejectsBadColors() {
        String svg = BadgeSvg.render("hash<mark>", "A & \"B\"", "red;}");
        assertTrue(svg.startsWith("<svg"));
        assertTrue(svg.contains("hash&lt;mark&gt;"));
        assertTrue(svg.contains("A &amp; &quot;B&quot;"));
        assertFalse(svg.contains("red;}"));
        assertTrue(svg.contains("#9f9f9f"));
    }
}
