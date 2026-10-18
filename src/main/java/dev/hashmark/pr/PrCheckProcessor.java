package dev.hashmark.pr;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.pr.GitHubPullRequestClient.ChangedFile;
import dev.hashmark.pr.GitHubPullRequestClient.Comparison;
import dev.hashmark.pr.PrDebtReport.NewDebt;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.config.RepoScanConfig;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.job.JobDeferral;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.service.DebtParserService;
import dev.hashmark.scanner.service.GitHubFileService;
import dev.hashmark.scanner.service.LabelRules;
import dev.hashmark.scanner.service.ScanFileFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Kuyruktan alınmış PR kontrolünü çalıştırır: değişen dosyalar → eklenen satırlardaki borçlar → bütçe →
 * commit durumu + PR yorumu. Depo taraması yapmaz, borç tablosuna yazmaz (PR henüz birleşmedi).
 *
 * <p>Maliyet: compare (1) + {@code .hashmark.yml} (1) + {@code .hashmarkignore} (1) + değişen dosya başına 1
 * (en çok {@link #MAX_FILES}) + durum/yorum (2–4) GitHub isteği.
 */
@Component
public class PrCheckProcessor {

    private static final Logger log = LoggerFactory.getLogger(PrCheckProcessor.class);
    static final int MAX_FILES = 100;

    private final ScanJobRepository scanJobRepository;
    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final AesEncryptionUtil aes;
    private final ConsentService consentService;
    private final GitHubFileService files;
    private final GitHubPullRequestClient github;
    private final DebtParserService parser;
    private final ScanProperties scanProperties;
    private final String appUrl;

    public PrCheckProcessor(ScanJobRepository scanJobRepository, RepoRepository repoRepository,
                            UserRepository userRepository, AesEncryptionUtil aes, ConsentService consentService,
                            GitHubFileService files, GitHubPullRequestClient github, DebtParserService parser,
                            ScanProperties scanProperties, @Value("${app.url:}") String appUrl) {
        this.scanJobRepository = scanJobRepository;
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.aes = aes;
        this.consentService = consentService;
        this.files = files;
        this.github = github;
        this.parser = parser;
        this.scanProperties = scanProperties;
        this.appUrl = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
    }

    public void process(ScanJob job) {
        UUID token = job.getClaimToken();
        try {
            Optional<PrDebtReport> report = run(job);
            String message = report.map(r -> "PR #" + r.prNumber() + ": " + r.statusDescription())
                    .orElse("PR #" + job.getPrNumber() + ": checks disabled in .hashmark.yml");
            scanJobRepository.complete(job.getId(), token, "DONE", report.map(r -> r.newDebts().size()).orElse(0),
                    0, 0, 0, job.getPrHeadSha(), message);
        } catch (ApiException e) {
            if (e.isTransient() && JobDeferral.isDeferrable(job, scanProperties)) {
                long delay = JobDeferral.delaySeconds(job, e.getRetryAfterSeconds(), scanProperties);
                scanJobRepository.defer(job.getId(), token, delay, "Retrying in " + delay + "s: " + e.getMessage());
                return;
            }
            log.warn("PR check failed for repo {} PR #{}: {}", job.getRepoId(), job.getPrNumber(), e.getMessage());
            scanJobRepository.complete(job.getId(), token, "FAILED", 0, 0, 0, 0, job.getPrHeadSha(), e.getMessage());
        } catch (Exception e) {
            log.error("PR check failed for repo " + job.getRepoId(), e);
            scanJobRepository.complete(job.getId(), token, "FAILED", 0, 0, 0, 0, job.getPrHeadSha(),
                    "Unexpected error while checking the pull request");
        }
    }

    /** @return rapor; {@code .hashmark.yml} hem yorumu hem durumu kapattıysa boş */
    Optional<PrDebtReport> run(ScanJob job) {
        Repo repo = repoRepository.findByIdAndUserId(job.getRepoId(), job.getUserId())
                .orElseThrow(() -> ApiException.notFound("Repo not found"));
        consentService.requireForProcessing(job.getUserId());
        consentService.requirePrivateRepoAck(repo);
        String token = githubToken(job.getUserId());
        String fullName = repo.getFullName();
        String head = job.getPrHeadSha();
        int pr = job.getPrNumber();

        // Kurallar PR'ın kendi commit'inden: PR .hashmark.yml'yi değiştiriyorsa yeni kurallar geçerli
        RepoScanConfig config = files.fetchRepoConfig(fullName, token, head);
        RepoScanConfig.PrSettings settings = config.pr();
        if (!settings.comment() && !settings.status()) {
            return Optional.empty();
        }

        PrDebtReport report = analyze(fullName, token, job, config);

        if (settings.status()) {
            github.setStatus(fullName, token, head, report.statusState(), report.statusDescription(),
                    dashboardUrl(repo), PrDebtReport.STATUS_CONTEXT);
        }
        if (settings.comment()) {
            // Bu arada PR'a yeni commit geldiyse eski sonuç güncel yorumu ezmesin (durum zaten commit'e özel)
            boolean current = github.currentHead(fullName, token, pr).map(head::equals).orElse(false);
            if (current) {
                // Hiç borç yoksa yeni yorum açılmaz (gürültü); önceki yorum varsa "temiz" diye güncellenir
                github.upsertComment(fullName, token, pr, PrDebtReport.MARKER, report.commentBody(dashboardUrl(repo)),
                        !report.newDebts().isEmpty());
            }
        }
        return Optional.of(report);
    }

    PrDebtReport analyze(String fullName, String token, ScanJob job, RepoScanConfig config) {
        String head = job.getPrHeadSha();
        Comparison diff = github.compare(fullName, token, job.getPrBaseSha(), head);
        ScanFileFilter filter = files.fileFilter(fullName, token, head, config);
        LabelRules rules = LabelRules.of(config);

        List<NewDebt> found = new ArrayList<>();
        int unchecked = diff.truncated() ? 1 : 0;
        int fetched = 0;
        for (ChangedFile file : diff.files()) {
            if ("removed".equals(file.status()) || !filter.shouldScan(file.path())) {
                continue;
            }
            if (file.patch() == null || fetched >= MAX_FILES) {
                unchecked++;
                continue;
            }
            Set<Integer> added = PatchParser.addedLines(file.patch());
            if (added.isEmpty()) {
                continue; // yalnızca silme / yeniden adlandırma
            }
            fetched++;
            Optional<String> content = github.fileAt(fullName, token, file.path(), head, files.maxFileSizeBytes());
            if (content.isEmpty()) {
                unchecked++;
                continue;
            }
            for (DebtDto d : parser.parse(content.get(), file.path(), rules)) {
                if (LabelRules.isDebt(d.getLabel()) && added.contains(d.getLineNo())) {
                    found.add(new NewDebt(d.getLabel(), d.getFilePath(), d.getLineNo(), d.getContent()));
                }
            }
        }
        return new PrDebtReport(fullName, job.getPrNumber(), head, found, unchecked, config.pr());
    }

    private String dashboardUrl(Repo repo) {
        return appUrl.isEmpty() ? null : appUrl + "/dashboard/repos/" + repo.getId();
    }

    private String githubToken(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
        if (user.getGithubToken() == null || user.getGithubToken().isBlank()) {
            throw ApiException.badRequest("GitHub account is not connected");
        }
        return aes.decrypt(user.getGithubToken());
    }
}
