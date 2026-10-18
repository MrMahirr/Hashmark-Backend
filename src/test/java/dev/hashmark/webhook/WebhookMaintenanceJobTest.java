package dev.hashmark.webhook;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookMaintenanceJobTest {

    @Test
    void upgradesOutdatedHooksAndToleratesFailures() {
        RepoRepository repos = mock(RepoRepository.class);
        UserRepository users = mock(UserRepository.class);
        AesEncryptionUtil aes = mock(AesEncryptionUtil.class);
        GitHubHookClient hooks = mock(GitHubHookClient.class);
        WebhookMaintenanceJob job = new WebhookMaintenanceJob(repos, users, aes, hooks, mock(WebhookDeliveryRepository.class));

        Repo ok = Repo.builder().id(1L).userId(10L).fullName("o/ok").webhookId(100L).build();
        Repo denied = Repo.builder().id(2L).userId(10L).fullName("o/denied").webhookId(200L).build();
        Repo disconnected = Repo.builder().id(3L).userId(11L).fullName("o/gone").webhookId(300L).build();
        when(repos.findOutdatedWebhooks(GitHubHookClient.EVENTS_VERSION, WebhookMaintenanceJob.BATCH))
                .thenReturn(List.of(ok, denied, disconnected));
        when(users.findById(10L)).thenReturn(Optional.of(User.builder().id(10L).githubToken("enc").build()));
        when(users.findById(11L)).thenReturn(Optional.of(User.builder().id(11L).build()));
        when(aes.decrypt("enc")).thenReturn("gho");
        when(hooks.addEvents("o/ok", "gho", 100L)).thenReturn(true);
        when(hooks.addEvents("o/denied", "gho", 200L))
                .thenThrow(ApiException.forbidden("Admin access to the repository is required to enable auto-scan"));

        assertEquals(1, job.upgradeOutdatedHooks());

        verify(repos).updateWebhookVersion(1L, GitHubHookClient.EVENTS_VERSION);
        verify(repos, never()).updateWebhookVersion(2L, GitHubHookClient.EVENTS_VERSION);
        verify(hooks, never()).addEvents(org.mockito.ArgumentMatchers.eq("o/gone"), org.mockito.ArgumentMatchers.any(), anyLong());
        verify(repos, never()).updateWebhookVersion(org.mockito.ArgumentMatchers.eq(3L), anyInt());
    }
}
