package dev.hashmark.issue;

import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.consent.ConsentService;
import dev.hashmark.debt.dto.DebtDto;
import dev.hashmark.debt.repository.DebtRepository;
import dev.hashmark.pr.PrDebtReport;
import dev.hashmark.repo.repository.RepoRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Borçtan GitHub Issue: tek tek ya da toplu. Aynı borç için ikinci istek yeni issue açmaz (mevcut olanı döner).
 * Issue kapanınca borç çözülmüş sayılır ({@code issues} webhook'u, otomatik tarama açıksa).
 */
@Service
public class DebtIssueService {

    public static final int MAX_BULK = 20;
    static final int MAX_TITLE = 100;

    private final DebtRepository debts;
    private final RepoRepository repos;
    private final UserRepository users;
    private final AesEncryptionUtil aes;
    private final ConsentService consent;
    private final GitHubIssueClient github;
    private final String appUrl;

    public DebtIssueService(DebtRepository debts, RepoRepository repos, UserRepository users, AesEncryptionUtil aes,
                            ConsentService consent, GitHubIssueClient github, @Value("${app.url:}") String appUrl) {
        this.debts = debts;
        this.repos = repos;
        this.users = users;
        this.aes = aes;
        this.consent = consent;
        this.github = github;
        this.appUrl = appUrl == null ? "" : appUrl.replaceAll("/+$", "");
    }

    /** @param created false ise borçta zaten issue vardı */
    public record IssueResult(Long debtId, Integer issueNumber, String issueUrl, boolean created, String error) {
    }

    public IssueResult createForDebt(Long userId, Long debtId) {
        consent.requireForProcessing(userId);
        return create(userId, githubToken(userId), debtId);
    }

    /** Hatalar borç başına raporlanır; biri başarısız olsa da diğerleri denenir. */
    public List<IssueResult> createForDebts(Long userId, List<Long> debtIds) {
        if (debtIds == null || debtIds.isEmpty()) {
            throw ApiException.badRequest("No debts selected");
        }
        LinkedHashSet<Long> unique = new LinkedHashSet<>(debtIds);
        if (unique.size() > MAX_BULK) {
            throw ApiException.badRequest("At most " + MAX_BULK + " issues can be created at once");
        }
        consent.requireForProcessing(userId);
        String token = githubToken(userId);
        List<IssueResult> results = new ArrayList<>();
        for (Long id : unique) {
            try {
                results.add(create(userId, token, id));
            } catch (ApiException e) {
                results.add(new IssueResult(id, null, null, false, e.getMessage()));
                if (e.isTransient() || e.getHttpStatus().value() == 401) {
                    break; // GitHub kesintisi / geçersiz token: kalanları denemek anlamsız
                }
            }
        }
        return results;
    }

    private IssueResult create(Long userId, String token, Long debtId) {
        DebtDto debt = debts.findByIdForUser(userId, debtId)
                .orElseThrow(() -> ApiException.notFound("Debt not found"));
        if (debt.getGithubIssueNumber() != null) {
            return new IssueResult(debtId, debt.getGithubIssueNumber(), debt.getGithubIssueUrl(), false, null);
        }
        if (debt.getResolvedAt() != null) {
            throw ApiException.badRequest("Debt is already resolved");
        }
        repos.findByIdAndUserId(debt.getRepoId(), userId).ifPresent(consent::requirePrivateRepoAck);

        GitHubIssueClient.CreatedIssue issue = github.create(debt.getRepoFullName(), token, title(debt), body(debt),
                List.of("hashmark", debt.getLabel().toLowerCase(java.util.Locale.ROOT)));
        if (!debts.linkGithubIssue(userId, debtId, issue.number(), issue.url())) {
            // Eşzamanlı ikinci istek daha önce bağladı: onun issue'su geçerli (bizimki GitHub'da fazladan kalır)
            DebtDto linked = debts.findByIdForUser(userId, debtId).orElse(debt);
            return new IssueResult(debtId, linked.getGithubIssueNumber(), linked.getGithubIssueUrl(), false, null);
        }
        return new IssueResult(debtId, issue.number(), issue.url(), true, null);
    }

    static String title(DebtDto debt) {
        String text = debt.getContent() == null ? "" : debt.getContent().replaceAll("\\s+", " ").trim();
        String title = debt.getLabel() + ": " + (text.isEmpty() ? debt.getFilePath() : text);
        return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE - 1) + "…" : title;
    }

    String body(DebtDto debt) {
        StringBuilder md = new StringBuilder();
        md.append("> ").append(PrDebtReport.escape(debt.getContent() == null ? "" : debt.getContent())).append("\n\n");
        md.append("| | |\n|---|---|\n");
        md.append("| Label | `").append(debt.getLabel()).append("` |\n");
        md.append("| Location | [").append(PrDebtReport.escape(debt.getFilePath())).append(":").append(debt.getLineNo())
          .append("](").append(debt.getPermalink()).append(") |\n");
        if (debt.getPriority() != null) {
            md.append("| Priority | ").append(debt.getPriority()).append(" |\n");
        }
        if (debt.getDueDate() != null) {
            md.append("| Due | ").append(debt.getDueDate()).append(" |\n");
        }
        if (debt.getAuthorLogin() != null) {
            // @etiketleme yok: yazara bildirim gitmesin, yalnızca bilgi
            md.append("| Introduced by | ").append(PrDebtReport.escape(debt.getAuthorLogin())).append(" |\n");
        }
        md.append("\n<sub>Created from a code comment by Hashmark");
        if (!appUrl.isEmpty()) {
            md.append(" · [Open in Hashmark](").append(appUrl).append("/dashboard/repos/").append(debt.getRepoId()).append(")");
        }
        md.append(". Closing this issue marks the debt as resolved.</sub>\n");
        md.append("<!-- hashmark:debt:").append(debt.getId()).append(" -->\n");
        return md.toString();
    }

    private String githubToken(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.unauthorized("User not found"));
        if (user.getGithubToken() == null || user.getGithubToken().isBlank()) {
            throw ApiException.badRequest("GitHub account is not connected");
        }
        return aes.decrypt(user.getGithubToken());
    }
}
