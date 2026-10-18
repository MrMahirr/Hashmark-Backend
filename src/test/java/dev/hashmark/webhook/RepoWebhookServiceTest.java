package dev.hashmark.webhook;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepoWebhookServiceTest {

    private RepoRepository repos;
    private GitHubHookClient hooks;
    private RepoWebhookService service;

    @BeforeEach
    void setUp() {
        repos = mock(RepoRepository.class);
        hooks = mock(GitHubHookClient.class);
        UserRepository users = mock(UserRepository.class);
        AesEncryptionUtil aes = mock(AesEncryptionUtil.class);
        WebhookProperties props = new WebhookProperties();
        props.setPublicUrl("https://hm.example/api/v1");

        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).githubToken("enc-token").build()));
        when(aes.decrypt("enc-token")).thenReturn("gho");
        when(aes.encrypt(anyString())).thenReturn("enc-new");
        service = new RepoWebhookService(repos, users, aes, hooks, props, mock(ConsentService.class));
    }

    private void repo(Long hookId, String secret) {
        when(repos.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(Repo.builder().id(5L).userId(1L)
                .fullName("o/r").tracked(true).webhookId(hookId).webhookSecret(secret).build()));
    }

    @Test
    void failedSecretRotationRestoresOldSecret() {
        repo(9L, "enc-old");
        when(hooks.updateSecret(eq("o/r"), eq("gho"), eq(9L), anyString(), anyString()))
                .thenThrow(ApiException.badGateway("GitHub returned unexpected status 500"));

        assertThrows(ApiException.class, () -> service.enable(1L, 5L));

        var order = inOrder(repos);
        order.verify(repos).updateWebhookSecret(5L, 1L, "enc-new");
        order.verify(repos).updateWebhookSecret(5L, 1L, "enc-old");
        verify(repos, never()).clearWebhook(5L, 1L);
    }

    @Test
    void failedFirstInstallClearsPendingSecret() {
        repo(null, null);
        when(hooks.createOrAdopt(eq("o/r"), eq("gho"), anyString(), anyString()))
                .thenThrow(ApiException.forbidden("Admin access to the repository is required to enable auto-scan"));

        assertThrows(ApiException.class, () -> service.enable(1L, 5L));

        verify(repos).clearWebhook(5L, 1L);
    }

    @Test
    void hookDeletedOnGitHubIsRecreated() {
        repo(9L, "enc-old");
        when(hooks.updateSecret(eq("o/r"), eq("gho"), eq(9L), anyString(), anyString())).thenReturn(false);
        when(hooks.createOrAdopt(eq("o/r"), eq("gho"), eq("https://hm.example/api/v1/webhooks/github/5"), anyString()))
                .thenReturn(42L);

        service.enable(1L, 5L);

        verify(repos).updateWebhookId(5L, 1L, 42L, GitHubHookClient.EVENTS_VERSION);
    }
}
