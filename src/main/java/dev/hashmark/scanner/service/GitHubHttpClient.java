package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.resilience.GitHubResilience;
import dev.hashmark.common.resilience.GitHubResilience.Lane;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.common.metrics.AppMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * GitHub'a giden tüm HTTP çağrıları için ortak istemci: kimlik başlıkları, rate-limit bekleme,
 * 5xx/ağ hatalarında jitter'lı üstel geri çekilme ve yönlendirmeleri elle izleme (token'ı başka hosta
 * sızdırmamak için). Her deneme {@link GitHubResilience} şeridinden ve devresinden geçer.
 */
@Component
public class GitHubHttpClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubHttpClient.class);
    /** Tarama işçisi: arka planda, beklemeyi göze alabilir. */
    private static final int SCAN_MAX_ATTEMPTS = 4;
    /** Kullanıcı isteği: tek yeniden deneme, rate-limit için bekleme yok (iş parçacığı asılı kalmasın). */
    private static final int INTERACTIVE_MAX_ATTEMPTS = 2;

    private final HttpClient httpClient;
    private final ScanProperties properties;
    private final AppMetrics metrics;
    private final GitHubResilience resilience;

    public GitHubHttpClient(ScanProperties properties) {
        this(properties, null, GitHubResilience.defaults());
    }

    public GitHubHttpClient(ScanProperties properties, AppMetrics metrics) {
        this(properties, metrics, GitHubResilience.defaults());
    }

    @Autowired
    public GitHubHttpClient(ScanProperties properties, AppMetrics metrics, GitHubResilience resilience) {
        this.properties = properties;
        this.metrics = metrics;
        this.resilience = resilience;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(properties.getHttpConnectTimeoutMs()))
                .build();
    }

    public String apiUrl() {
        return properties.getGithubApiUrl();
    }

    public HttpRequest.Builder request(URI uri, String token) {
        // timeout: yanıt başlıkları gelene kadar geçen süre. Gövde akışı tarama süresi sınırıyla korunur.
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.getHttpResponseTimeoutMs()))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Hashmark");
    }

    /** Tarama şeridinden gönderir. */
    public HttpResponse<InputStream> send(HttpRequest request) {
        return send(request, Lane.SCAN);
    }

    /**
     * Rate-limit (403/429 + sınır başlıkları) için makul süreler beklenir (yalnızca tarama şeridinde); 5xx ve ağ
     * hatalarında yalnızca idempotent istekler yeniden denenir. Devre açıksa ya da şerit doluysa hemen 503 döner
     * (yeniden denenmez). Başarısız olursa anlamlı bir {@link ApiException} fırlatır.
     * <p>Şerit izni yanıt başlıkları gelince bırakılır; gövde akışı (tarball) tarama süresi sınırıyla korunur.
     */
    public HttpResponse<InputStream> send(HttpRequest request, Lane lane) {
        int maxAttempts = !isIdempotent(request) ? 1
                : lane == Lane.SCAN ? SCAN_MAX_ATTEMPTS : INTERACTIVE_MAX_ATTEMPTS;
        for (int attempt = 1; ; attempt++) {
            try (GitHubResilience.Permit permit = resilience.acquire(lane)) {
                HttpResponse<InputStream> response;
                try {
                    response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                } catch (IOException e) {
                    permit.failure(e);
                    throw e;
                }
                int status = response.statusCode();
                if (status >= 500) {
                    permit.failure(new IOException("GitHub responded " + status));
                } else {
                    permit.success();
                }
                recordRateLimit(response.headers());

                if (status == 403 || status == 429) {
                    long waitMillis = rateLimitWaitMillis(response.headers());
                    if (waitMillis >= 0 || status == 429) {
                        closeQuietly(response.body());
                        long maxWait = properties.getMaxRateLimitWaitSeconds() * 1000L;
                        if (lane == Lane.SCAN && waitMillis >= 0 && waitMillis <= maxWait && attempt < maxAttempts) {
                            log.info("GitHub rate limit hit, waiting {} ms", waitMillis);
                            // Sınır sıfırlanınca tüm işçiler aynı anda dönmesin
                            sleep(waitMillis + ThreadLocalRandom.current().nextLong(1_000));
                            continue;
                        }
                        throw ApiException.tooManyRequests("GITHUB_RATE_LIMITED",
                                "GitHub API rate limit exceeded" + resetHint(response.headers()) + ". Please try again later",
                                waitMillis > 0 ? (waitMillis + 999) / 1000 : 60);
                    }
                    return response; // rate-limit değil: gerçek 403 (yetki)
                }

                if (status >= 500 && attempt < maxAttempts) {
                    closeQuietly(response.body());
                    sleep(backoffMillis(attempt));
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= maxAttempts) {
                    throw ApiException.badGateway("Could not reach GitHub: " + e.getClass().getSimpleName());
                }
                sleep(backoffMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.serviceUnavailable("Scan was interrupted");
            }
        }
    }

    /**
     * Yeniden denemesi güvenli istekler: GET/HEAD ve blame için kullanılan GraphQL sorgusu (POST ama salt okuma;
     * projede GraphQL mutation yok). Diğer POST/PUT/DELETE istekleri tek denemedir.
     */
    static boolean isIdempotent(HttpRequest request) {
        String method = request.method();
        return "GET".equals(method) || "HEAD".equals(method)
                || ("POST".equals(method) && request.uri().getPath().endsWith("/graphql"));
    }

    private void recordRateLimit(HttpHeaders headers) {
        if (metrics == null) {
            return;
        }
        headers.firstValue("X-RateLimit-Remaining").ifPresent(v -> {
            try {
                metrics.githubRateLimitRemaining(Long.parseLong(v.trim()));
            } catch (NumberFormatException ignored) {
                // geçersiz başlık yok sayılır
            }
        });
    }

    public static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // yok sayılır
        }
    }

    /** Beklenecek süre (ms); rate-limit değilse -1. */
    private static long rateLimitWaitMillis(HttpHeaders headers) {
        Optional<String> retryAfter = headers.firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            try {
                return Long.parseLong(retryAfter.get().trim()) * 1000L;
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        if ("0".equals(headers.firstValue("X-RateLimit-Remaining").orElse(null))) {
            try {
                long reset = Long.parseLong(headers.firstValue("X-RateLimit-Reset").orElse("0"));
                return Math.max(0, reset * 1000L - System.currentTimeMillis()) + 1000L;
            } catch (NumberFormatException ignored) {
                return 60_000L;
            }
        }
        return -1;
    }

    private static String resetHint(HttpHeaders headers) {
        try {
            long reset = Long.parseLong(headers.firstValue("X-RateLimit-Reset").orElse(""));
            return " (resets at " + Instant.ofEpochSecond(reset) + ")";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    /**
     * Üstel geri çekilme + jitter: taban 1, 2, 4 sn; gerçek bekleme tabanın %50–%150'si. Jitter, GitHub
     * toparlanırken tüm işçilerin aynı anda yeniden denemesini (thundering herd) önler.
     */
    static long backoffMillis(int attempt) {
        long base = 1000L * (1L << (attempt - 1));
        return base / 2 + ThreadLocalRandom.current().nextLong(base + 1);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.serviceUnavailable("Scan was interrupted");
        }
    }
}
