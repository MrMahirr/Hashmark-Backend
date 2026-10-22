package dev.hashmark.badge;

import dev.hashmark.badge.BadgeService.BadgeData;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** README rozeti: depo sahibi için yönetim, herkes için SVG ve shields.io endpoint JSON'u. */
@RestController
@Tag(name = "Badge")
public class BadgeController {

    /** Rozet bayatlığı: GitHub'ın görsel vekili ve shields.io önbelleğe alır; tarama sonrası en geç 5 dk. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();
    private static final MediaType SVG = MediaType.parseMediaType("image/svg+xml;charset=utf-8");
    private static final String LABEL = "hashmark";

    public record BadgeInfo(boolean enabled, String token) {
    }

    private final BadgeService badgeService;

    public BadgeController(BadgeService badgeService) {
        this.badgeService = badgeService;
    }

    @GetMapping("/repos/{repoId}/badge")
    @Operation(summary = "README rozeti durumu")
    public BadgeInfo get(@AuthenticationPrincipal Long userId, @PathVariable Long repoId) {
        return badgeService.getToken(userId, repoId).map(t -> new BadgeInfo(true, t)).orElse(new BadgeInfo(false, null));
    }

    @PostMapping("/repos/{repoId}/badge")
    @Operation(summary = "Rozeti ac ya da anahtari yenile (eski baglanti calismaz)")
    public BadgeInfo rotate(@AuthenticationPrincipal Long userId, @PathVariable Long repoId) {
        return new BadgeInfo(true, badgeService.rotate(userId, repoId));
    }

    @DeleteMapping("/repos/{repoId}/badge")
    @Operation(summary = "Rozeti kapat")
    public ResponseEntity<Void> disable(@AuthenticationPrincipal Long userId, @PathVariable Long repoId) {
        badgeService.disable(userId, repoId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/public/badge/{token}.svg")
    @Operation(summary = "README rozeti (SVG)")
    public ResponseEntity<String> svg(@PathVariable String token) {
        return badgeService.publicBadge(token)
                .map(b -> ResponseEntity.ok().cacheControl(CACHE).contentType(SVG)
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                        .body(BadgeSvg.render(LABEL, b.message(), b.color())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).contentType(SVG)
                        .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)))
                        .body(BadgeSvg.render(LABEL, "unknown", "#9f9f9f")));
    }

    /** shields.io endpoint şeması: {@code https://img.shields.io/endpoint?url=<bu adres>} */
    @GetMapping(value = "/public/badge/{token}.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "README rozeti (shields.io endpoint JSON)")
    public ResponseEntity<Map<String, Object>> shields(@PathVariable String token) {
        BadgeData data = badgeService.publicBadge(token).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", 1);
        body.put("label", LABEL);
        body.put("message", data == null ? "unknown" : data.message());
        body.put("color", data == null ? "lightgrey" : data.color().substring(1));
        body.put("cacheSeconds", 300);
        return ResponseEntity.status(data == null ? HttpStatus.NOT_FOUND : HttpStatus.OK).cacheControl(CACHE).body(body);
    }
}
