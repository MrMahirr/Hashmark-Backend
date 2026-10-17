package dev.hashmark.scanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "scan")
public class ScanProperties {

    private long maxFileSizeBytes = 1_048_576L;
    private int maxFiles = 20_000;
    private long maxDurationSeconds = 900;
    private long maxRateLimitWaitSeconds = 60;
    /** GitHub'a bağlantı kurma zaman aşımı. */
    private long httpConnectTimeoutMs = 3_000;
    /** GitHub'dan yanıt başlıklarının gelmesi için zaman aşımı (gövde akışı hariç). */
    private long httpResponseTimeoutMs = 10_000;
    private String githubApiUrl = "https://api.github.com";

    /** Aynı anda çalışacak tarama sayısı (bu instance için). */
    private int maxConcurrent = 2;
    /** Kuyruğun kontrol aralığı. */
    private long dispatchIntervalMs = 2000;
    /** Bu süre kalp atışı gelmeyen RUNNING iş yeniden kuyruğa alınır. */
    private long heartbeatTimeoutMinutes = 5;
    /** Bir iş en fazla bu kadar kez denenir. */
    private int maxAttempts = 3;
    /** Bir taramada git blame için en fazla kaç dosya sorgulanır (0 = kapalı). */
    private int blameMaxFiles = 25;

    /** Geçici hatada (GitHub kesintisi, rate-limit) iş en fazla bu kadar ertelenir, sonra FAILED. */
    private int maxDeferrals = 8;
    /** İlk erteleme süresi; her ertelemede ikiye katlanır (+ jitter). */
    private long deferBaseSeconds = 30;
    /** Tek ertelemenin üst sınırı. */
    private long deferMaxSeconds = 900;
    /**
     * Düzgün kapanışta çalışan taramaların bitmesi için beklenecek süre. Dolunca kalanlar kuyruğa geri bırakılır
     * (başka instance ya da yeniden açılış hemen devralır; kalp atışı zaman aşımı beklenmez).
     */
    private long shutdownGraceSeconds = 20;
    /** Kuyruk üst sınırı (tüm kullanıcılar): dolunca yeni tarama 503 alır. Sınırsız kuyruk yok. */
    private int maxQueueDepth = 500;

    /** Kullanıcı kotaları (0 = sınırsız). */
    private Quota quota = new Quota();

    @Data
    public static class Quota {
        /** Kullanıcının aynı anda kuyrukta + çalışan en fazla tarama sayısı. */
        private int maxActivePerUser = 3;
        /** Kullanıcının son 24 saatte başlatabileceği tarama sayısı. */
        private int maxPerDay = 50;
        /** Kullanıcının takip edebileceği depo sayısı. */
        private int maxTrackedRepos = 50;
        /** GitHub'ın bildirdiği depo boyutu (MB) bundan büyükse takip/tarama reddedilir. */
        private long maxRepoSizeMb = 1024;
    }

    private List<String> excludedDirs = List.of(
            "node_modules", "vendor", "dist", "build", "out", "target", ".git", ".next", ".nuxt",
            ".gradle", ".idea", ".vscode", "coverage", "__pycache__", ".venv", "venv", "site-packages",
            "bower_components", "Pods", "third_party", "third-party");
}
