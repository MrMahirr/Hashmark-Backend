package dev.hashmark.badge;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.repo.repository.RepoRepository.BadgeState;
import dev.hashmark.report.dto.DebtInsights;
import dev.hashmark.report.dto.LabelStats;
import dev.hashmark.report.repository.ReportRepository;
import dev.hashmark.report.service.HealthScore;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * README rozeti. Depo sahibi açınca tahmin edilemez bir anahtar üretilir; rozet adresi yalnızca bu anahtarla
 * çalışır (depo kimliği tahmin edilerek özel depoların notu okunamaz). Yeniden üretmek eski bağlantıyı geçersiz kılar.
 * Rozet yalnızca not ve skor gösterir; borç içeriği, dosya yolu ya da depo adı açığa çıkmaz.
 */
@Service
public class BadgeService {

    /** 32 bayt → 43 karakter base64url (dolgusuz). */
    private static final int TOKEN_BYTES = 32;
    public static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");

    /** Herkese açık rozet verisi: {@code scanned == false} ise not yok. */
    public record BadgeData(boolean scanned, String grade, int score) {
        public String message() {
            return scanned ? grade + " · " + score : "not scanned";
        }

        /** shields.io adlandırılmış renkleriyle aynı tonlar. */
        public String color() {
            if (!scanned) {
                return "#9f9f9f";
            }
            return switch (grade) {
                case "A" -> "#44cc11";
                case "B" -> "#97ca00";
                case "C" -> "#dfb317";
                case "D" -> "#fe7d37";
                default -> "#e05d44";
            };
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final RepoRepository repoRepository;
    private final ReportRepository reportRepository;
    private final ConsentService consentService;

    public BadgeService(RepoRepository repoRepository, ReportRepository reportRepository,
                        ConsentService consentService) {
        this.repoRepository = repoRepository;
        this.reportRepository = reportRepository;
        this.consentService = consentService;
    }

    public BadgeState getState(Long userId, Long repoId) {
        requireTracked(userId, repoId);
        return repoRepository.findBadgeState(repoId, userId).orElse(new BadgeState(null, false));
    }

    /** Sayfayı açmak için anahtar gerekir: rozet kapalıysa önce anahtar üretilir (rozet de açılmış olur). */
    public BadgeState setPublicPage(Long userId, Long repoId, boolean enabled) {
        BadgeState state = getState(userId, repoId);
        if (enabled && state.token() == null) {
            repoRepository.updateBadgeToken(repoId, userId, newToken());
        }
        repoRepository.updatePublicPage(repoId, userId, enabled);
        return getState(userId, repoId);
    }

    /** Rozeti açar ya da anahtarı yeniler (eski README bağlantıları çalışmaz). */
    public String rotate(Long userId, Long repoId) {
        requireTracked(userId, repoId);
        String token = newToken();
        repoRepository.updateBadgeToken(repoId, userId, token);
        return token;
    }

    public void disable(Long userId, Long repoId) {
        requireTracked(userId, repoId);
        repoRepository.updateBadgeToken(repoId, userId, null);
    }

    /**
     * Kimliksiz istek: depo sistem kapsamında anahtarla bulunur. Onay geri çekildiyse (kapı açıkken) kullanıcının
     * verisi işlenmez: bilinmiyor gibi davranılır.
     */
    private Optional<Repo> publicRepo(String token) {
        if (token == null || !TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        Optional<Repo> repo = TenantContext.asSystem(() -> repoRepository.findByBadgeToken(token));
        if (repo.isEmpty()) {
            return Optional.empty();
        }
        Repo r = repo.get();
        if (TenantContext.asUser(r.getUserId(), () -> consentService.isProcessingBlocked(r.getUserId()))) {
            return Optional.empty();
        }
        return repo;
    }

    /** İçgörü sahibin kapsamında hesaplanır. */
    public Optional<BadgeData> publicBadge(String token) {
        Optional<Repo> repo = publicRepo(token);
        if (repo.isEmpty()) {
            return Optional.empty();
        }
        Repo r = repo.get();
        if (r.getLastScannedAt() == null) {
            return Optional.of(new BadgeData(false, null, 0));
        }
        DebtInsights insights = TenantContext.asUser(r.getUserId(),
                () -> reportRepository.getInsights(r.getUserId(), r.getId()).build(null, null));
        HealthScore health = HealthScore.of(insights.getWeightedDebt());
        return Optional.of(new BadgeData(true, health.grade(), health.score()));
    }

    /** Yalnızca sahip sayfayı açtıysa; taranmamış depoda boş sayılarla döner. */
    public Optional<PublicScore> publicScore(String token) {
        Optional<Repo> repo = publicRepo(token).filter(r -> Boolean.TRUE.equals(r.getPublicPage()));
        if (repo.isEmpty()) {
            return Optional.empty();
        }
        Repo r = repo.get();
        return Optional.of(TenantContext.asUser(r.getUserId(), () -> {
            DebtInsights insights = reportRepository.getInsights(r.getUserId(), r.getId()).build(null, null);
            HealthScore health = HealthScore.of(insights.getWeightedDebt());
            List<PublicScore.Week> trend = reportRepository.getTrendData(r.getUserId(), r.getId()).stream()
                    .map(p -> new PublicScore.Week(p.getWeekStart(), p.getTotalDebts())).toList();
            boolean scanned = r.getLastScannedAt() != null;
            return new PublicScore(r.getFullName(), Boolean.TRUE.equals(r.getIsPrivate()), r.getLastScannedAt(),
                    scanned ? health.grade() : null, scanned ? health.score() : 0, insights.getOpenDebts(),
                    insights.getStaleCount(), insights.getRotScore(), insights.getAverageAgeDays(),
                    debtLabels(reportRepository.getLabelStats(r.getUserId(), r.getId())), insights.getAgeBuckets(),
                    trend);
        }));
    }

    private static Map<String, Integer> debtLabels(LabelStats stats) {
        Map<String, Integer> labels = new LinkedHashMap<>();
        labels.put("TODO", stats.getTodoCount());
        labels.put("FIXME", stats.getFixmeCount());
        labels.put("HACK", stats.getHackCount());
        labels.put("XXX", stats.getXxxCount());
        labels.putAll(stats.getCustomCounts());
        return labels;
    }

    private void requireTracked(Long userId, Long repoId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repository not found"));
        if (!Boolean.TRUE.equals(repo.getTracked())) {
            throw ApiException.notFound("Repository not found");
        }
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
