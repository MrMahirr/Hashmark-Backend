package dev.hashmark.webhook;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.pr.GitHubPullRequestClient;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.service.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * GitHub teslimatını işler. İstek kimliksizdir: depo sistem kapsamında bulunur, imza depo anahtarıyla
 * doğrulanır, iş yalnızca depo sahibinin kapsamında kuyruğa alınır (RLS).
 */
@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);
    private static final String BRANCH_REF_PREFIX = "refs/heads/";

    /** @param result pong | queued | coalesced | already_queued | ignored | skipped | duplicate | resolved | reopened */
    public record Delivery(String result, Long jobId, String reason) {
        static Delivery ignored(String reason) {
            return new Delivery("ignored", null, reason);
        }

        static Delivery of(ScanService.AutoScanResult r) {
            return new Delivery(r.outcome().toLowerCase(java.util.Locale.ROOT), r.jobId(), r.reason());
        }
    }

    /** Kontrol edilen PR olayları: açılış, yeni commit, yeniden açılış, taslaktan çıkış. */
    static final Set<String> PR_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private final RepoRepository repoRepository;
    private final AesEncryptionUtil aes;
    private final ScanService scanService;
    private final WebhookDeliveryRepository deliveries;
    private final DebtRepository debtRepository;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public GitHubWebhookService(RepoRepository repoRepository, AesEncryptionUtil aes, ScanService scanService,
                                WebhookDeliveryRepository deliveries, DebtRepository debtRepository) {
        this.repoRepository = repoRepository;
        this.aes = aes;
        this.scanService = scanService;
        this.deliveries = deliveries;
        this.debtRepository = debtRepository;
    }

    public Delivery handle(long repoId, String event, String signature, byte[] body) {
        return handle(repoId, event, null, signature, body);
    }

    /**
     * @param deliveryId {@code X-GitHub-Delivery}; aynı kimlikle ikinci teslimat {@code duplicate} döner
     * @throws ApiException 404 (depo yok / otomatik tarama kapalı), 401 (imza geçersiz), 400 (gövde bozuk)
     */
    public Delivery handle(long repoId, String event, String deliveryId, String signature, byte[] body) {
        Repo repo = TenantContext.asSystem(() -> repoRepository.findWebhookTarget(repoId))
                .orElseThrow(() -> ApiException.notFound("Unknown webhook"));
        if (!WebhookSignature.isValid(aes.decrypt(repo.getWebhookSecret()), body, signature)) {
            throw ApiException.unauthorized("Invalid webhook signature");
        }
        // İmzadan SONRA: doğrulanmamış istekler tekilleştirme tablosunu dolduramaz
        if (!TenantContext.asSystem(() -> deliveries.firstSeen(deliveryId))) {
            return new Delivery("duplicate", null, "delivery already processed");
        }

        if ("ping".equals(event)) {
            return new Delivery("pong", null, null);
        }
        if (!"push".equals(event) && !"pull_request".equals(event) && !"issues".equals(event)) {
            return Delivery.ignored("event '" + clip(event) + "' is not handled");
        }

        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("Malformed webhook payload");
        }
        if ("pull_request".equals(event)) {
            return handlePullRequest(repo, payload);
        }
        if ("issues".equals(event)) {
            return handleIssue(repo, payload);
        }

        String ignoreReason = ignoreReason(repo, payload);
        if (ignoreReason != null) {
            return Delivery.ignored(ignoreReason);
        }
        ScanService.AutoScanResult queued = TenantContext.asUser(repo.getUserId(),
                () -> scanService.enqueueAutomatic(repo));
        log.info("Push webhook for repo {}: {}", repo.getId(), queued.outcome());
        return Delivery.of(queued);
    }

    private Delivery handlePullRequest(Repo repo, JsonNode payload) {
        if (!sameRepository(repo, payload)) {
            return Delivery.ignored("repository does not match");
        }
        String action = payload.path("action").asText("");
        if (!PR_ACTIONS.contains(action)) {
            return Delivery.ignored("pull request action '" + clip(action) + "' is not checked");
        }
        JsonNode pr = payload.path("pull_request");
        int number = pr.path("number").asInt(0);
        String head = pr.path("head").path("sha").asText("");
        String base = pr.path("base").path("sha").asText("");
        if (number <= 0 || !GitHubPullRequestClient.isSha(head) || !GitHubPullRequestClient.isSha(base)) {
            throw ApiException.badRequest("Malformed pull request payload");
        }
        ScanService.AutoScanResult queued = TenantContext.asUser(repo.getUserId(),
                () -> scanService.enqueuePrCheck(repo, number, head, base));
        log.info("Pull request webhook for repo {} PR #{}: {}", repo.getId(), number, queued.outcome());
        return Delivery.of(queued);
    }

    /** Hashmark'ın açtığı issue kapanınca borç çözülür, yeniden açılınca geri açılır. */
    private Delivery handleIssue(Repo repo, JsonNode payload) {
        if (!sameRepository(repo, payload)) {
            return Delivery.ignored("repository does not match");
        }
        String action = payload.path("action").asText("");
        if (!"closed".equals(action) && !"reopened".equals(action)) {
            return Delivery.ignored("issue action '" + clip(action) + "' is not tracked");
        }
        int number = payload.path("issue").path("number").asInt(0);
        if (number <= 0) {
            throw ApiException.badRequest("Malformed issue payload");
        }
        boolean closed = "closed".equals(action);
        int updated = TenantContext.asUser(repo.getUserId(),
                () -> debtRepository.applyGithubIssueState(repo.getId(), number, closed));
        if (updated == 0) {
            return Delivery.ignored("issue is not linked to a debt");
        }
        log.info("Issue #{} of repo {} {}: {} debt(s) updated", number, repo.getId(), action, updated);
        return new Delivery(closed ? "resolved" : "reopened", null, updated + " debt(s)");
    }

    private static boolean sameRepository(Repo repo, JsonNode payload) {
        String githubId = payload.path("repository").path("id").asText("");
        return repo.getGithubRepoId() == null || repo.getGithubRepoId().equals(githubId);
    }

    /** @return push taranmayacaksa sebebi, taranacaksa null */
    static String ignoreReason(Repo repo, JsonNode push) {
        JsonNode repository = push.path("repository");
        // Hook başka bir depoya taşınmış/kopyalanmışsa (aynı adres, aynı anahtar) yanlış depo taranmasın
        if (!sameRepository(repo, push)) {
            return "repository does not match";
        }
        if (push.path("deleted").asBoolean(false)) {
            return "branch deleted";
        }
        String ref = push.path("ref").asText("");
        if (!ref.startsWith(BRANCH_REF_PREFIX)) {
            return "not a branch push";
        }
        String branch = ref.substring(BRANCH_REF_PREFIX.length());
        String tracked = repo.getTrackedBranch() != null
                ? repo.getTrackedBranch()
                : repository.path("default_branch").asText("");
        if (!branch.equals(tracked)) {
            return "push to a branch that is not tracked";
        }
        return null;
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        String safe = value.replaceAll("[^A-Za-z0-9_.-]", "");
        return safe.length() > 40 ? safe.substring(0, 40) : safe;
    }
}
