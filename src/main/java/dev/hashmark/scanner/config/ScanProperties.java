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
    private long staleJobMinutes = 30;
    private long maxRateLimitWaitSeconds = 60;
    private String githubApiUrl = "https://api.github.com";
    private List<String> excludedDirs = List.of(
            "node_modules", "vendor", "dist", "build", "out", "target", ".git", ".next", ".nuxt",
            ".gradle", ".idea", ".vscode", "coverage", "__pycache__", ".venv", "venv", "site-packages",
            "bower_components", "Pods", "third_party", "third-party");
}
