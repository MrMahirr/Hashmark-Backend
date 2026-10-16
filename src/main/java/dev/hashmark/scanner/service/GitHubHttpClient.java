package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.config.ScanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * GitHub'a giden tüm HTTP çağrıları için ortak istemci: kimlik başlıkları, rate-limit bekleme,
 * 5xx/ağ hatalarında üstel geri çekilme ve yönlendirmeleri elle izleme (token'ı başka hosta sızdırmamak için).
 */
@Component
public class GitHubHttpClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubHttpClient.class);
    private static final int MAX_ATTEMPTS = 4;

    private final HttpClient httpClient;
    private final ScanProperties properties;

    public GitHubHttpClient(ScanProperties properties) {
        this.properties = properties;
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

    /**
     * Rate-limit (403/429 + sınır başlıkları) için makul süreler beklenir; 5xx ve ağ hatalarında
     * yeniden denenir. Başarısız olursa anlamlı bir {@link ApiException} fırlatır.
     */
    public HttpResponse<InputStream> send(HttpRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (status == 403 || status == 429) {
                    long waitMillis = rateLimitWaitMillis(response.headers());
                    if (waitMillis >= 0 || status == 429) {
                        closeQuietly(response.body());
                        long maxWait = properties.getMaxRateLimitWaitSeconds() * 1000L;
                        if (waitMillis >= 0 && waitMillis <= maxWait && attempt < MAX_ATTEMPTS) {
                            log.info("GitHub rate limit hit, waiting {} ms", waitMillis);
                            sleep(waitMillis);
                            continue;
                        }
                        throw ApiException.tooManyRequests(
                                "GitHub API rate limit exceeded" + resetHint(response.headers()) + ". Please try again later");
                    }
                    return response; // rate-limit değil: gerçek 403 (yetki)
                }

                if (status >= 500 && attempt < MAX_ATTEMPTS) {
                    closeQuietly(response.body());
                    sleep(backoffMillis(attempt));
                    continue;
                }
                return response;
            } catch (IOException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw ApiException.badGateway("Could not reach GitHub: " + e.getClass().getSimpleName());
                }
                sleep(backoffMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.serviceUnavailable("Scan was interrupted");
            }
        }
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

    private static long backoffMillis(int attempt) {
        return 1000L * (1L << (attempt - 1)); // 1s, 2s, 4s
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
