package dev.hashmark.issue;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.resilience.GitHubResilience;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.repo.repository.RepoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DebtIssueServiceTest {

    private DebtRepository debts;
    private GitHubIssueClient github;
    private DebtIssueService service;

    @BeforeEach
    void setUp() {
        debts = mock(DebtRepository.class);
        github = mock(GitHubIssueClient.class);
        UserRepository users = mock(UserRepository.class);
        AesEncryptionUtil aes = mock(AesEncryptionUtil.class);
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).githubToken("enc").build()));
        when(aes.decrypt("enc")).thenReturn("gho");
        service = new DebtIssueService(debts, mock(RepoRepository.class), users, aes, mock(ConsentService.class),
                github, "https://hm.example");
    }

    private DebtDto debt(long id) {
        return DebtDto.builder().id(id).repoId(10L).repoFullName("o/r").filePath("src/a.ts").lineNo(3)
                .label("FIXME").content("handle @octocat <script> #12").permalink("https://github.com/o/r/blob/s/src/a.ts#L3")
                .authorLogin("octocat").build();
    }

    @Test
    void createsLinkedIssueWithSafeBody() {
        when(debts.findByIdForUser(1L, 5L)).thenReturn(Optional.of(debt(5)));
        when(github.create(eq("o/r"), eq("gho"), anyString(), anyString(), any()))
                .thenReturn(new GitHubIssueClient.CreatedIssue(42, "https://github.com/o/r/issues/42"));
        when(debts.linkGithubIssue(1L, 5L, 42, "https://github.com/o/r/issues/42")).thenReturn(true);

        DebtIssueService.IssueResult result = service.createForDebt(1L, 5L);

        assertTrue(result.created());
        assertEquals(42, result.issueNumber());
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).create(eq("o/r"), eq("gho"), title.capture(), body.capture(), eq(List.of("hashmark", "fixme")));
        assertEquals("FIXME: handle @octocat <script> #12", title.getValue());
        assertFalse(body.getValue().contains("@octocat"), "issue gövdesi etiketleme bildirimi üretmemeli");
        assertFalse(body.getValue().contains("<script>"));
        assertTrue(body.getValue().contains("https://github.com/o/r/blob/s/src/a.ts#L3"));
        assertTrue(body.getValue().contains("https://hm.example/dashboard/repos/10"));
        assertTrue(body.getValue().contains("<!-- hashmark:debt:5 -->"));
    }

    @Test
    void existingIssueIsReturnedNotDuplicated() {
        DebtDto linked = debt(5);
        linked.setGithubIssueNumber(7);
        linked.setGithubIssueUrl("https://github.com/o/r/issues/7");
        when(debts.findByIdForUser(1L, 5L)).thenReturn(Optional.of(linked));

        DebtIssueService.IssueResult result = service.createForDebt(1L, 5L);

        assertFalse(result.created());
        assertEquals(7, result.issueNumber());
        verify(github, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void resolvedOrForeignDebtsAreRejected() {
        DebtDto resolved = debt(5);
        resolved.setResolvedAt(LocalDateTime.now());
        when(debts.findByIdForUser(1L, 5L)).thenReturn(Optional.of(resolved));
        when(debts.findByIdForUser(1L, 6L)).thenReturn(Optional.empty());

        assertThrows(ApiException.class, () -> service.createForDebt(1L, 5L));
        assertEquals(404, assertThrows(ApiException.class, () -> service.createForDebt(1L, 6L)).getHttpStatus().value());
    }

    @Test
    void bulkReportsPerDebtAndStopsOnGithubOutage() {
        when(debts.findByIdForUser(eq(1L), any())).thenAnswer(inv -> Optional.of(debt(inv.getArgument(1))));
        when(debts.findByIdForUser(1L, 2L)).thenReturn(Optional.empty());
        when(github.create(any(), any(), any(), any(), any()))
                .thenReturn(new GitHubIssueClient.CreatedIssue(1, "u"))
                .thenThrow(ApiException.serviceUnavailable(GitHubResilience.CODE_UNAVAILABLE, "GitHub is unavailable", 30));
        when(debts.linkGithubIssue(eq(1L), any(), org.mockito.ArgumentMatchers.anyInt(), any())).thenReturn(true);

        List<DebtIssueService.IssueResult> results = service.createForDebts(1L, List.of(1L, 2L, 3L, 4L, 1L));

        assertEquals(3, results.size(), "tekrar eden kimlik bir kez; kesintiden sonra kalanlar denenmez");
        assertTrue(results.get(0).created());
        assertEquals("Debt not found", results.get(1).error());
        assertEquals("GitHub is unavailable", results.get(2).error());
        assertNull(results.get(2).issueNumber());
        verify(github, times(2)).create(any(), any(), any(), any(), any());
    }

    @Test
    void bulkLimits() {
        assertThrows(ApiException.class, () -> service.createForDebts(1L, List.of()));
        List<Long> many = new java.util.ArrayList<>();
        for (long i = 1; i <= DebtIssueService.MAX_BULK + 1; i++) {
            many.add(i);
        }
        assertThrows(ApiException.class, () -> service.createForDebts(1L, many));
        assertThrows(ApiException.class, () -> service.createForDebts(1L, Collections.emptyList()));
    }

    @Test
    void longTitlesAreClipped() {
        DebtDto d = debt(5);
        d.setContent("x".repeat(300));

        assertEquals(DebtIssueService.MAX_TITLE, DebtIssueService.title(d).length());
    }
}
