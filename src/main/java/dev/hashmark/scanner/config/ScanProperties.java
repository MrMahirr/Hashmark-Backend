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

    private List<String> excludedDirs = List.of(
            "node_modules", "vendor", "dist", "build", "out", "target", ".git", ".next", ".nuxt",
            ".gradle", ".idea", ".vscode", "coverage", "__pycache__", ".venv", "venv", "site-packages",
            "bower_components", "Pods", "third_party", "third-party");
}
