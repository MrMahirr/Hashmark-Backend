package dev.hashmark.pr;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.resilience.GitHubResilience;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.pr.GitHubPullRequestClient.ChangedFile;
import dev.hashmark.pr.GitHubPullRequestClient.Comparison;
import dev.hashmark.repo.model.Repo;
import dev.hashmark.repo.repository.RepoRepository;
import dev.hashmark.scanner.config.RepoScanConfig;
import dev.hashmark.scanner.config.ScanProperties;
import dev.hashmark.scanner.model.ScanJob;
import dev.hashmark.scanner.repository.ScanJobRepository;
import dev.hashmark.scanner.service.DebtParserService;
import dev.hashmark.scanner.service.GitHubFileService;
import dev.hashmark.scanner.service.ScanFileFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrCheckProcessorTest {

    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String HEAD = "2222222222222222222222222222222222222222";

    private ScanJobRepository jobs;
    private GitHubFileService files;
    private GitHubPullRequestClient github;
    private PrCheckProcessor processor;
    private final UUID token = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jobs = mock(ScanJobRepository.class);
        files = mock(GitHubFileService.class);
        github = mock(GitHubPullRequestClient.class);
        RepoRepository repos = mock(RepoRepository.class);
        UserRepository users = mock(UserRepository.class);
        AesEncryptionUtil aes = mock(AesEncryptionUtil.class);

        when(repos.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(Repo.builder().id(10L).userId(1L)
                .fullName("o/r").tracked(true).build()));
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().id(1L).githubToken("enc").build()));
        when(aes.decrypt("enc")).thenReturn("gho");
        when(files.fileFilter(eq("o/r"), eq("gho"), eq(HEAD), any()))
                .thenReturn(new ScanFileFilter(Set.of("vendor"), "generated/\n"));
        when(files.maxFileSizeBytes()).thenReturn(1_000_000L);
        when(github.currentHead("o/r", "gho", 7)).thenReturn(Optional.of(HEAD));

        processor = new PrCheckProcessor(jobs, repos, users, aes, mock(ConsentService.class), files, github,
                new DebtParserService(), new ScanProperties(), "https://hm.example/");
    }

    private ScanJob job() {
        return ScanJob.builder().id(5L).repoId(10L).userId(1L).kind("PR").prNumber(7).prHeadSha(HEAD)
                .prBaseSha(BASE).status("RUNNING").claimToken(token).deferrals(0).build();
    }

    private void config(String yaml) {
        when(files.fetchRepoConfig("o/r", "gho", HEAD)).thenReturn(RepoScanConfig.parse(yaml));
    }

    private void diff(ChangedFile... changed) {
        when(github.compare("o/r", "gho", BASE, HEAD)).thenReturn(new Comparison(List.of(changed), false));
    }

    @Test
    void countsOnlyDebtsOnAddedLinesAndHonoursFilters() {
        config("pr: {budget: {max-new: 1}}");
        diff(new ChangedFile("src/a.ts", "modified", "@@ -1,2 +1,3 @@\n // TODO: old\n+// FIXME: new one\n x();"),
             new ChangedFile("vendor/lib.ts", "added", "@@ -0,0 +1 @@\n+// TODO: vendored"),
             new ChangedFile("generated/x.ts", "added", "@@ -0,0 +1 @@\n+// TODO: generated"),
             new ChangedFile("gone.ts", "removed", null),
             new ChangedFile("big.ts", "modified", null),
             new ChangedFile("src/b.ts", "added", "@@ -0,0 +1,2 @@\n+// HACK: two\n+// NOTE: info only"));
        when(github.fileAt("o/r", "gho", "src/a.ts", HEAD, 1_000_000L))
                .thenReturn(Optional.of("// TODO: old\n// FIXME: new one\nx();\n"));
        when(github.fileAt("o/r", "gho", "src/b.ts", HEAD, 1_000_000L))
                .thenReturn(Optional.of("// HACK: two\n// NOTE: info only\n"));

        processor.process(job());

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(github).setStatus(eq("o/r"), eq("gho"), eq(HEAD), eq("failure"), description.capture(),
                eq("https://hm.example/dashboard/repos/10"), eq(PrDebtReport.STATUS_CONTEXT));
        assertEquals("2 new debt markers, over budget: total 2/1", description.getValue());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(github).upsertComment(eq("o/r"), eq("gho"), eq(7), eq(PrDebtReport.MARKER), body.capture(), eq(true));
        assertTrue(body.getValue().contains("src/a.ts:2"), body.getValue());
        assertTrue(body.getValue().contains("src/b.ts:1"));
        assertTrue(!body.getValue().contains("old"), "PR'dan önce var olan borç sayılmaz");
        assertTrue(body.getValue().contains("1 changed file(s) could not be checked"), "big.ts (patch yok)");
        verify(github, never()).fileAt(anyString(), anyString(), eq("vendor/lib.ts"), anyString(), anyLong());

        verify(jobs).complete(eq(5L), eq(token), eq("DONE"), eq(2), anyInt(), anyInt(), anyInt(), eq(HEAD),
                eq("PR #7: 2 new debt markers, over budget: total 2/1"));
    }

    @Test
    void cleanPrDoesNotOpenANewComment() {
        config(null);
        diff(new ChangedFile("src/a.ts", "modified", "@@ -1 +1 @@\n-x\n+y();"));
        when(github.fileAt(anyString(), anyString(), anyString(), anyString(), anyLong())).thenReturn(Optional.of("y();\n"));

        processor.process(job());

        verify(github).setStatus(any(), any(), any(), eq("success"), eq("0 new debt markers"), any(), any());
        verify(github).upsertComment(any(), any(), anyInt(), any(), any(), eq(false));
    }

    @Test
    void staleRunDoesNotOverwriteTheComment() {
        config(null);
        diff();
        when(github.currentHead("o/r", "gho", 7)).thenReturn(Optional.of("3333333333333333333333333333333333333333"));

        processor.process(job());

        verify(github).setStatus(any(), any(), eq(HEAD), any(), any(), any(), any());
        verify(github, never()).upsertComment(any(), any(), anyInt(), any(), any(), anyBoolean());
    }

    @Test
    void configCanDisableCommentAndStatus() {
        config("pr: {comment: false, status: false}");

        processor.process(job());

        verify(github, never()).compare(any(), any(), any(), any());
        verify(jobs).complete(eq(5L), eq(token), eq("DONE"), eq(0), anyInt(), anyInt(), anyInt(), eq(HEAD),
                eq("PR #7: checks disabled in .hashmark.yml"));
    }

    @Test
    void githubOutageDefersTheCheck() {
        config(null);
        when(github.compare(any(), any(), any(), any()))
                .thenThrow(ApiException.serviceUnavailable(GitHubResilience.CODE_UNAVAILABLE, "down", 30));
        when(jobs.defer(eq(5L), eq(token), anyLong(), anyString())).thenReturn(true);

        processor.process(job());

        verify(jobs).defer(eq(5L), eq(token), anyLong(), anyString());
        verify(jobs, never()).complete(anyLong(), any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void permanentErrorFailsTheJob() {
        config(null);
        when(github.compare(any(), any(), any(), any())).thenThrow(ApiException.forbidden("Could not compare commits"));

        processor.process(job());

        verify(jobs).complete(eq(5L), eq(token), eq("FAILED"), eq(0), anyInt(), anyInt(), anyInt(), eq(HEAD),
                eq("Could not compare commits"));
    }
}
