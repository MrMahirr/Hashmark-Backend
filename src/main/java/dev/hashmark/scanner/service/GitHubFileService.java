package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.scanner.config.ScanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * GitHub deposunu <b>tek bir tarball isteğiyle</b> indirip akış halinde işler.
 *
 * <p>Eski yaklaşım (ağaç + dosya başına {@code /contents} isteği) 5000 istek/saat limitine hızla
 * çarpıyordu; ağaç yanıtı büyük depolarda kesiliyor ve 1 MB üstü dosyalar boş dönüyordu.
 * Bu sınıf ayrıca rate-limit bekleme, 5xx/ağ hatalarında üstel geri çekilme ve çalışma süresi /
 * dosya sayısı sınırlarını yönetir.
 */
@Service
public class GitHubFileService {

    private static final Logger log = LoggerFactory.getLogger(GitHubFileService.class);

    private static final Pattern REPO_FULL_NAME = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final int MAX_ATTEMPTS = 4;
    private static final int PROGRESS_EVERY = 50;
    private static final int BINARY_SNIFF_BYTES = 8000;
    private static final int MAX_IGNORE_FILE_BYTES = 64 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER) // yönlendirmeyi elle izleyip token'ı başka hosta sızdırmıyoruz
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private final ScanProperties properties;

    public GitHubFileService(ScanProperties properties) {
        this.properties = properties;
    }

    /** Her taranan dosya için çağrılır. */
    @FunctionalInterface
    public interface FileHandler {
        void handle(String path, String content) throws Exception;
    }

    /**
     * @param scannedPaths başarıyla işlenen dosyalar
     * @param keepOpenPaths işlenemeyen / boyut nedeniyle atlanan dosyalar (borçları çözülmüş sayılmamalı)
     * @param truncated    sınıra takıldığı için depo kısmen tarandı mı
     */
    public record ScanResult(int scanned, int skipped, int failed, Set<String> scannedPaths,
                             Set<String> keepOpenPaths, boolean truncated, String truncationReason) {
    }

    public ScanResult scanRepository(String repoFullName, String githubToken, FileHandler handler,
                                     IntConsumer progress) {
        if (repoFullName == null || !REPO_FULL_NAME.matcher(repoFullName).matches()) {
            throw ApiException.badRequest("Invalid repository name");
        }

        Instant deadline = Instant.now().plusSeconds(properties.getMaxDurationSeconds());
        ScanFileFilter filter = new ScanFileFilter(
                Set.copyOf(properties.getExcludedDirs()), fetchIgnoreFile(repoFullName, githubToken));

        Set<String> scannedPaths = new HashSet<>();
        Set<String> keepOpen = new HashSet<>();
        int scanned = 0;
        int skipped = 0;
        int failed = 0;
        boolean truncated = false;
        String reason = null;

        Optional<InputStream> archive = openArchive(repoFullName, githubToken);
        if (archive.isEmpty()) { // boş depo
            return new ScanResult(0, 0, 0, scannedPaths, keepOpen, false, null);
        }

        try (TarArchiveReader tar = new TarArchiveReader(new GZIPInputStream(archive.get(), 64 * 1024))) {
            TarArchiveReader.Entry entry;
            while ((entry = tar.next()) != null) {
                String path = stripRootDirectory(entry.path());
                if (path == null || !filter.shouldScan(path)) {
                    continue;
                }
                if (Instant.now().isAfter(deadline)) {
                    truncated = true;
                    reason = "Time limit of " + properties.getMaxDurationSeconds() + "s reached";
                    break;
                }
                if (scanned + failed >= properties.getMaxFiles()) {
                    truncated = true;
                    reason = "File limit of " + properties.getMaxFiles() + " reached";
                    break;
                }
                if (entry.size() > properties.getMaxFileSizeBytes()) {
                    skipped++;
                    keepOpen.add(path);
                    continue;
                }

                byte[] data = tar.readEntry();
                if (looksBinary(data)) {
                    skipped++;
                    continue;
                }

                try {
                    handler.handle(path, new String(data, StandardCharsets.UTF_8));
                    scanned++;
                    scannedPaths.add(path);
                } catch (Exception e) {
                    failed++;
                    keepOpen.add(path);
                    log.warn("Failed to process file {} of {}", path, repoFullName, e);
                }

                if (progress != null && (scanned + failed) % PROGRESS_EVERY == 0) {
                    progress.accept(scanned + failed);
                }
            }
        } catch (IOException e) {
            log.warn("Archive stream failed for {}", repoFullName, e);
            throw ApiException.badGateway("Failed to download the repository archive from GitHub");
        }

        return new ScanResult(scanned, skipped, failed, scannedPaths, keepOpen, truncated, reason);
    }

    // ------------------------------------------------------------------
    // GitHub istekleri
    // ------------------------------------------------------------------

    /** Repo kökündeki {@code .hashmarkignore}; yoksa veya okunamazsa {@code null}. */
    private String fetchIgnoreFile(String repoFullName, String token) {
        HttpRequest request = baseRequest(URI.create(properties.getGithubApiUrl() + "/repos/" + repoFullName + "/contents/.hashmarkignore"), token)
                .header("Accept", "application/vnd.github.raw+json")
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<InputStream> response = sendWithRetry(request);
        try (InputStream body = response.body()) {
            if (response.statusCode() == 200) {
                return new String(body.readNBytes(MAX_IGNORE_FILE_BYTES), StandardCharsets.UTF_8);
            }
            if (response.statusCode() == 401) {
                throw ApiException.unauthorized("GitHub token is invalid or revoked, please sign in again");
            }
            return null; // 404 ve diğerleri: kural dosyası yok sayılır
        } catch (IOException e) {
            log.warn("Could not read .hashmarkignore of {}", repoFullName, e);
            return null;
        }
    }

    /** Varsayılan dal tarball'ı; boş depo (409) için {@code Optional.empty()}. */
    private Optional<InputStream> openArchive(String repoFullName, String token) {
        HttpRequest api = baseRequest(URI.create(properties.getGithubApiUrl() + "/repos/" + repoFullName + "/tarball"), token)
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        HttpResponse<InputStream> response = sendWithRetry(api);

        int status = response.statusCode();
        if (status == 301 || status == 302 || status == 307 || status == 308) {
            closeQuietly(response.body());
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> ApiException.badGateway("GitHub archive redirect had no location"));
            // codeload adresi imzalı; Authorization başlığı gönderilmez
            HttpRequest download = HttpRequest.newBuilder(URI.create(location))
                    .header("User-Agent", "Hashmark")
                    .timeout(Duration.ofSeconds(60))
                    .GET().build();
            response = sendWithRetry(download);
            status = response.statusCode();
        }

        if (status == 200) {
            return Optional.of(response.body());
        }
        closeQuietly(response.body());
        switch (status) {
            case 401 -> throw ApiException.unauthorized("GitHub token is invalid or revoked, please sign in again");
            case 403 -> throw ApiException.forbidden("GitHub denied access to this repository");
            case 404 -> throw ApiException.notFound("Repository not found or not accessible with your GitHub token");
            case 409 -> {
                return Optional.empty();
            }
            default -> throw ApiException.badGateway("GitHub returned unexpected status " + status);
        }
    }

    private HttpRequest.Builder baseRequest(URI uri, String token) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Hashmark");
    }

    /**
     * Rate-limit (403/429 + sınır başlıkları) için makul süreler beklenir; 5xx ve ağ hatalarında
     * üstel geri çekilmeyle yeniden denenir. Başarısız olursa anlamlı bir {@link ApiException} fırlatır.
     */
    private HttpResponse<InputStream> sendWithRetry(HttpRequest request) {
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

    // ------------------------------------------------------------------

    private static String stripRootDirectory(String entryPath) {
        int slash = entryPath.indexOf('/');
        if (slash < 0 || slash == entryPath.length() - 1) {
            return null;
        }
        return entryPath.substring(slash + 1);
    }

    private static boolean looksBinary(byte[] data) {
        int limit = Math.min(data.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // yok sayılır
        }
    }
}
