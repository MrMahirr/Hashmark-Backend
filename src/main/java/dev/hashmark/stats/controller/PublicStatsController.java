package dev.hashmark.stats.controller;

import dev.hashmark.common.tenant.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Landing sayfası için kimlik doğrulaması gerektirmeyen, kişisel veri içermeyen toplam sayaçlar. */
@RestController
@RequestMapping("/public")
@Tag(name = "Public")
public class PublicStatsController {

    public record PublicStats(long trackedMarkers, long repositories) {
    }

    private final JdbcTemplate jdbcTemplate;

    public PublicStatsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/stats")
    @Operation(summary = "Landing sayaclari: izlenen isaret ve tarama yapilan depo sayisi")
    public PublicStats stats() {
        // Herkese açık toplam sayaçlar kullanıcılar arasıdır; yalnızca sayı döner, satır değil (RLS, V11)
        return TenantContext.asSystem(this::count);
    }

    private PublicStats count() {
        Long markers = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM debts d JOIN repos r ON r.id = d.repo_id WHERE r.tracked AND d.resolved_at IS NULL",
                Long.class);
        Long repos = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repos WHERE tracked AND last_scanned_at IS NOT NULL", Long.class);
        return new PublicStats(markers == null ? 0 : markers, repos == null ? 0 : repos);
    }
}
