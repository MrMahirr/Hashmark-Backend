package dev.hashmark.webhook;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Otomatik taramayı açar/kapatır: depoya GitHub push hook'u kurar ya da kaldırır. İmza anahtarı depo başına
 * rastgeledir ve AES ile şifreli saklanır.
 */
@Service
public class RepoWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RepoWebhookService.class);

    private final RepoRepository repoRepository;
    private final UserRepository userRepository;
    private final AesEncryptionUtil aes;
    private final GitHubHookClient hooks;
    private final WebhookProperties properties;
    private final ConsentService consentService;

    public RepoWebhookService(RepoRepository repoRepository, UserRepository userRepository, AesEncryptionUtil aes,
                              GitHubHookClient hooks, WebhookProperties properties, ConsentService consentService) {
        this.repoRepository = repoRepository;
        this.userRepository = userRepository;
        this.aes = aes;
        this.hooks = hooks;
        this.properties = properties;
        this.consentService = consentService;
    }

    public void enable(Long userId, Long repoId) {
        // Önce sahiplik: başkasının deposu her durumda 404 (K4)
        Repo repo = trackedRepo(userId, repoId);
        if (!properties.isEnabled()) {
            throw ApiException.serviceUnavailable("Auto-scan is not available on this server");
        }
        consentService.requireForProcessing(userId);
        consentService.requirePrivateRepoAck(repo);
        String token = githubToken(userId);

        String url = properties.hookUrl(repoId);
        String secret = WebhookSignature.newSecret();
        // Önce anahtar: GitHub hook kurulur kurulmaz ping gönderir, imzası doğrulanabilsin
        repoRepository.updateWebhookSecret(repoId, userId, aes.encrypt(secret));
        try {
            Long existing = repo.getWebhookId();
            long hookId = existing != null && hooks.updateSecret(repo.getFullName(), token, existing, url, secret)
                    ? existing
                    : hooks.createOrAdopt(repo.getFullName(), token, url, secret);
            repoRepository.updateWebhookId(repoId, userId, hookId);
        } catch (RuntimeException e) {
            if (repo.getWebhookId() == null) {
                repoRepository.clearWebhook(repoId, userId);
            }
            throw e;
        }
    }

    /** Hook GitHub'dan silinemese bile (yetki kaybı) yerel kayıt temizlenir; teslimatlar artık 404 alır. */
    public void disable(Long userId, Long repoId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found"));
        removeFromGitHubQuietly(repo);
        repoRepository.clearWebhook(repoId, userId);
    }

    /** Depo silinmeden önce: hook'u GitHub'dan kaldırmayı dener, hata fırlatmaz. */
    public void removeQuietly(Long userId, Long repoId) {
        repoRepository.findByIdAndUserId(repoId, userId).ifPresent(this::removeFromGitHubQuietly);
    }

    /** GitHub bağlantısı kesilmeden / hesap silinmeden önce (token hâlâ geçerliyken) çağrılır. */
    public void removeAllQuietly(Long userId) {
        try {
            for (Repo repo : repoRepository.findWithWebhookByUserId(userId)) {
                removeFromGitHubQuietly(repo);
                repoRepository.clearWebhook(repo.getId(), userId);
            }
        } catch (Exception e) {
            log.warn("Could not clean up webhooks of user {}", userId, e);
        }
    }

    private void removeFromGitHubQuietly(Repo repo) {
        if (repo.getWebhookId() == null) {
            return;
        }
        try {
            User user = userRepository.findById(repo.getUserId()).orElse(null);
            if (user == null || user.getGithubToken() == null || user.getGithubToken().isBlank()) {
                return;
            }
            if (!hooks.delete(repo.getFullName(), aes.decrypt(user.getGithubToken()), repo.getWebhookId())) {
                log.warn("GitHub refused to delete webhook {} of repo {}", repo.getWebhookId(), repo.getId());
            }
        } catch (Exception e) {
            log.warn("Could not delete webhook {} of repo {}", repo.getWebhookId(), repo.getId(), e);
        }
    }

    private Repo trackedRepo(Long userId, Long repoId) {
        Repo repo = repoRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> ApiException.notFound("Repo not found"));
        if (!Boolean.TRUE.equals(repo.getTracked())) {
            throw ApiException.badRequest("Repository is not tracked; add it first");
        }
        return repo;
    }

    private String githubToken(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
        if (user.getGithubToken() == null || user.getGithubToken().isBlank()) {
            throw ApiException.badRequest("GitHub account is not connected");
        }
        return aes.decrypt(user.getGithubToken());
    }
}
