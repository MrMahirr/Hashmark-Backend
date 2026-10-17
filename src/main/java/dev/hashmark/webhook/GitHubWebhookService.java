package dev.hashmark.webhook;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.tenant.TenantContext;
import dev.hashmark.common.util.AesEncryptionUtil;
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

/**
 * GitHub teslimatını işler. İstek kimliksizdir: depo sistem kapsamında bulunur, imza depo anahtarıyla
 * doğrulanır, iş yalnızca depo sahibinin kapsamında kuyruğa alınır (RLS).
 */
@Service
public class GitHubWebhookService {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookService.class);
    private static final String BRANCH_REF_PREFIX = "refs/heads/";

    /** @param result pong | queued | coalesced | already_queued | ignored | skipped */
    public record Delivery(String result, Long jobId, String reason) {
        static Delivery ignored(String reason) {
            return new Delivery("ignored", null, reason);
        }
    }

    private final RepoRepository repoRepository;
    private final AesEncryptionUtil aes;
    private final ScanService scanService;
    private final ObjectMapper mapper = JsonMapper.builder().build();

    public GitHubWebhookService(RepoRepository repoRepository, AesEncryptionUtil aes, ScanService scanService) {
        this.repoRepository = repoRepository;
        this.aes = aes;
        this.scanService = scanService;
    }

    /**
     * @throws ApiException 404 (depo yok / otomatik tarama kapalı), 401 (imza geçersiz), 400 (gövde bozuk)
     */
    public Delivery handle(long repoId, String event, String signature, byte[] body) {
        Repo repo = TenantContext.asSystem(() -> repoRepository.findWebhookTarget(repoId))
                .orElseThrow(() -> ApiException.notFound("Unknown webhook"));
        if (!WebhookSignature.isValid(aes.decrypt(repo.getWebhookSecret()), body, signature)) {
            throw ApiException.unauthorized("Invalid webhook signature");
        }

        if ("ping".equals(event)) {
            return new Delivery("pong", null, null);
        }
        if (!"push".equals(event)) {
            return Delivery.ignored("event '" + clip(event) + "' is not handled");
        }

        JsonNode push;
        try {
            push = mapper.readTree(body);
        } catch (JacksonException e) {
            throw ApiException.badRequest("Malformed webhook payload");
        }
        String ignoreReason = ignoreReason(repo, push);
        if (ignoreReason != null) {
            return Delivery.ignored(ignoreReason);
        }

        ScanService.AutoScanResult queued = TenantContext.asUser(repo.getUserId(),
                () -> scanService.enqueueAutomatic(repo));
        log.info("Push webhook for repo {}: {}", repo.getId(), queued.outcome());
        return new Delivery(queued.outcome().toLowerCase(java.util.Locale.ROOT), queued.jobId(), queued.reason());
    }

    /** @return push taranmayacaksa sebebi, taranacaksa null */
    static String ignoreReason(Repo repo, JsonNode push) {
        JsonNode repository = push.path("repository");
        // Hook başka bir depoya taşınmış/kopyalanmışsa (aynı adres, aynı anahtar) yanlış depo taranmasın
        String githubId = repository.path("id").asText("");
        if (repo.getGithubRepoId() != null && !repo.getGithubRepoId().equals(githubId)) {
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
