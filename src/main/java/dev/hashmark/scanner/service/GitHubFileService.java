package dev.hashmark.scanner.service;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.LogSafe;
import dev.hashmark.scanner.config.RepoScanConfig;
import dev.hashmark.scanner.config.RepoScanConfig;
import dev.hashmark.scanner.config.ScanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
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

import static dev.hashmark.scanner.service.GitHubHttpClient.closeQuietly;

/**
 * GitHub deposunu <b>tek bir tarball isteğiyle</b> indirip akış halinde işler.
 *
 * <p>Eski yaklaşım (ağaç + dosya başına {@code /contents} isteği) 5000 istek/saat limitine hızla
 * çarpıyordu; ağaç yanıtı büyük depolarda kesiliyor ve 1 MB üstü dosyalar boş dönüyordu.
 * Taramadan önce kök dizindeki {@code .hashmark.yml} ve {@code .hashmarkignore} birer istekle okunur.
 * Taramadan önce kök dizindeki {@code .hashmark.yml} ve {@code .hashmarkignore} ayrı birer istekle okunur.
 * HTTP ayrıntıları (rate-limit, yeniden deneme) {@link GitHubHttpClient} içindedir.
 */
@Service
public class GitHubFileService {

    private static final Logger log = LoggerFactory.getLogger(GitHubFileService.class);

    private static final Pattern REPO_FULL_NAME = Pattern.compile("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    private static final Pattern BRANCH_NAME = Pattern.compile("^[A-Za-z0-9._/-]{1,255}$");
    private static final int PROGRESS_EVERY = 50;
    private static final long HEARTBEAT_MILLIS = 20_000L;
    private static final int BINARY_SNIFF_BYTES = 8000;
    private static final int MAX_IGNORE_FILE_BYTES = 64 * 1024;
    private static final int MAX_CONFIG_FILE_BYTES = 64 * 1024;

    private final ScanProperties properties;
    private final GitHubHttpClient http;

    public GitHubFileService(ScanProperties properties, GitHubHttpClient http) {
        this.properties = properties;
        this.http = http;
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
     * @param commitSha    taranan commit (arşiv başlığından; bilinmiyorsa {@code null})
     */
    public record ScanResult(int scanned, int skipped, int failed, Set<String> scannedPaths,
                             Set<String> keepOpenPaths, boolean truncated, String truncationReason,
                             String commitSha) {
    }

    /** Dal adı güvenli mi? ({@code null} veya boş = varsayılan dal) */
    public static boolean isValidBranch(String branch) {
        return branch != null && BRANCH_NAME.matcher(branch).matches() && !branch.contains("..")
                && !branch.startsWith("/") && !branch.endsWith("/");
    }

    /**
     * İlgili daldaki {@code .hashmark.yml} (1 istek). Dosya yoksa varsayılan; hatalıysa varsayılan + uyarı.
     *
     * @param branch {@code null} ise deponun varsayılan dalı
     */
    public RepoScanConfig fetchRepoConfig(String repoFullName, String githubToken, String branch) {
        String ref = validatedRef(repoFullName, branch);
        String content = fetchRootFile(repoFullName, githubToken, ref, RepoScanConfig.FILE_NAME,
                MAX_CONFIG_FILE_BYTES + 1);
        if (content != null && content.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_FILE_BYTES) {
            return RepoScanConfig.invalid(RepoScanConfig.FILE_NAME + " is larger than 64 KB");
        }
        return RepoScanConfig.parse(content);
    }

    /**
     * Taramayla aynı dosya kuralları (desteklenen uzantı, hariç dizinler, {@code .hashmarkignore} ve
     * {@code .hashmark.yml} include/exclude); PR kontrolü değişen dosyaları bununla süzer.
     *
     * @param ref dal ya da commit; {@code null} = varsayılan dal
     */
    public ScanFileFilter fileFilter(String repoFullName, String githubToken, String ref, RepoScanConfig config) {
        String validRef = validatedRef(repoFullName, ref);
        return new ScanFileFilter(Set.copyOf(properties.getExcludedDirs()),
                fetchRootFile(repoFullName, githubToken, validRef, ".hashmarkignore", MAX_IGNORE_FILE_BYTES),
                config.include(), config.exclude());
    }

    /** Taramada atlanan dosya boyutu sınırı (PR kontrolü de aynı sınırı uygular). */
    public long maxFileSizeBytes() {
        return properties.getMaxFileSizeBytes();
    }

    /** {@code .hashmark.yml} olmadan tarar (varsayılan yapılandırma). */
    public ScanResult scanRepository(String repoFullName, String githubToken, String branch,
                                     FileHandler handler, IntConsumer progress) {
        return scanRepository(repoFullName, githubToken, branch, RepoScanConfig.DEFAULT, handler, progress);
    }

    /**
     * @param branch {@code null} ise deponun varsayılan dalı taranır
     * @param config {@link #fetchRepoConfig} sonucu (include/exclude burada uygulanır)
     * @param progress her {@value #PROGRESS_EVERY} dosyada ve en az 20 sn'de bir çağrılır (kalp atışı)
     */
    public ScanResult scanRepository(String repoFullName, String githubToken, String branch, RepoScanConfig config,
                                     FileHandler handler, IntConsumer progress) {
        String ref = validatedRef(repoFullName, branch);

        Instant deadline = Instant.now().plusSeconds(properties.getMaxDurationSeconds());
        ScanFileFilter filter = new ScanFileFilter(
                Set.copyOf(properties.getExcludedDirs()),
                fetchRootFile(repoFullName, githubToken, ref, ".hashmarkignore", MAX_IGNORE_FILE_BYTES),
                config.include(), config.exclude());

        Set<String> scannedPaths = new HashSet<>();
        Set<String> keepOpen = new HashSet<>();
        int scanned = 0;
        int skipped = 0;
        int failed = 0;
        boolean truncated = false;
        String reason = null;
        String commitSha = null;

        Optional<InputStream> archive = openArchive(repoFullName, githubToken, ref);
        if (archive.isEmpty()) { // boş depo
            return new ScanResult(0, 0, 0, scannedPaths, keepOpen, false, null, null);
        }

        long lastBeat = System.currentTimeMillis();
        try (TarArchiveReader tar = new TarArchiveReader(new GZIPInputStream(archive.get(), 64 * 1024))) {
            TarArchiveReader.Entry entry;
            while ((entry = tar.next()) != null) {
                if (commitSha == null) {
                    commitSha = tar.globalCommitSha();
                }
                if (progress != null && System.currentTimeMillis() - lastBeat >= HEARTBEAT_MILLIS) {
                    progress.accept(scanned + failed);
                    lastBeat = System.currentTimeMillis();
                }

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
                    // İstisna mesajı dosya içeriğinden parça taşıyabilir (K1/K2): yalnızca tür + konum.
                    log.warn("Failed to process file {} of {}: {}", path, repoFullName, LogSafe.describe(e));
                }

                if (progress != null && (scanned + failed) % PROGRESS_EVERY == 0) {
                    progress.accept(scanned + failed);
                    lastBeat = System.currentTimeMillis();
                }
            }
            if (commitSha == null) {
                commitSha = tar.globalCommitSha();
            }
        } catch (IOException e) {
            log.warn("Archive stream failed for {}", repoFullName, e);
            throw ApiException.badGateway("Failed to download the repository archive from GitHub");
        }

        return new ScanResult(scanned, skipped, failed, scannedPaths, keepOpen, truncated, reason, commitSha);
    }

    // ------------------------------------------------------------------
    // GitHub istekleri
    // ------------------------------------------------------------------

    /** Depo adını ve dalı doğrular; varsayılan dal için {@code null} döner. */
    private static String validatedRef(String repoFullName, String branch) {
        if (repoFullName == null || !REPO_FULL_NAME.matcher(repoFullName).matches()) {
            throw ApiException.badRequest("Invalid repository name");
        }
        if (branch != null && !branch.isBlank() && !isValidBranch(branch)) {
            throw ApiException.badRequest("Invalid branch name");
        }
        return (branch == null || branch.isBlank()) ? null : branch;
    }

    /** İlgili daldaki kök dosya ({@code .hashmarkignore}, {@code .hashmark.yml}); yoksa veya okunamazsa {@code null}. */
    private String fetchRootFile(String repoFullName, String token, String ref, String fileName, int maxBytes) {
        String url = http.apiUrl() + "/repos/" + repoFullName + "/contents/" + fileName
                + (ref == null ? "" : "?ref=" + URLEncoder.encode(ref, StandardCharsets.UTF_8));
        HttpRequest request = http.request(URI.create(url), token)
                .header("Accept", "application/vnd.github.raw+json")
                .timeout(Duration.ofSeconds(20))
                .GET().build();
        HttpResponse<InputStream> response = http.send(request);
        try (InputStream body = response.body()) {
            if (response.statusCode() == 200) {
                return new String(body.readNBytes(maxBytes), StandardCharsets.UTF_8);
            }
            if (response.statusCode() == 401) {
                throw ApiException.unauthorized("GitHub token is invalid or revoked, please sign in again");
            }
            return null; // 404 ve diğerleri: kural dosyası yok sayılır
        } catch (IOException e) {
            log.warn("Could not read {} of {}", fileName, repoFullName, e);
            return null;
        }
    }

    /** Dal (veya varsayılan dal) tarball'ı; boş depo (409) için {@code Optional.empty()}. */
    private Optional<InputStream> openArchive(String repoFullName, String token, String ref) {
        String url = http.apiUrl() + "/repos/" + repoFullName + "/tarball"
                + (ref == null ? "" : "/" + encodeRef(ref));
        HttpRequest api = http.request(URI.create(url), token)
                .timeout(Duration.ofSeconds(60))
                .GET().build();
        HttpResponse<InputStream> response = http.send(api);

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
            response = http.send(download);
            status = response.statusCode();
        }

        if (status == 200) {
            return Optional.of(response.body());
        }
        closeQuietly(response.body());
        switch (status) {
            case 401 -> throw ApiException.unauthorized("GitHub token is invalid or revoked, please sign in again");
            case 403 -> throw ApiException.forbidden("GitHub denied access to this repository");
            case 404 -> throw ApiException.notFound(ref == null
                    ? "Repository not found or not accessible with your GitHub token"
                    : "Repository or branch '" + ref + "' not found or not accessible");
            case 409 -> {
                return Optional.empty();
            }
            default -> throw ApiException.badGateway("GitHub returned unexpected status " + status);
        }
    }

    // ------------------------------------------------------------------

    /** Dal adındaki her parçayı kodlar, '/' ayraçlarını korur. */
    private static String encodeRef(String ref) {
        StringBuilder out = new StringBuilder();
        for (String part : ref.split("/")) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out.toString();
    }

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
}
