package dev.hashmark.auth.service;

import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.auth.repository.UserRepository.StoredToken;
import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.common.util.AesEncryptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Anahtar dönüşü (docs/KEY_ROTATION.md): geçerli anahtarla şifrelenmemiş GitHub token'larını (önceki anahtar
 * ya da önekli olmayan eski biçim) partiler halinde yeniden şifreler. İdempotent; iki instance aynı anda
 * çalışsa da koşullu UPDATE aynı satırı iki kez değiştirmez. Çözülemeyen değer (anahtarı listeden erken
 * çıkarılmış) atlanır ve sayılır; o kullanıcı yeniden giriş yapınca token yenilenir.
 */
@Component
public class GithubTokenReencryptionJob {

    private static final Logger log = LoggerFactory.getLogger(GithubTokenReencryptionJob.class);
    static final int BATCH_SIZE = 200;

    private final UserRepository users;
    private final AesEncryptionUtil crypto;
    private final AppMetrics metrics;
    private final boolean enabled;

    public GithubTokenReencryptionJob(UserRepository users, AesEncryptionUtil crypto, AppMetrics metrics,
                                      @Value("${encryption.reencrypt.enabled:true}") boolean enabled) {
        this.users = users;
        this.crypto = crypto;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public record Result(int reencrypted, int failed) {}

    /** Açılıştan 1 dk sonra, sonra günde bir. Dönüş sonrası iş yoksa yalnızca tek boş sorgu çalışır. */
    @Scheduled(initialDelayString = "${encryption.reencrypt.initial-delay-ms:60000}",
               fixedDelayString = "${encryption.reencrypt.interval-ms:86400000}")
    @SchedulerLock(name = "github-token-reencrypt", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            run();
        } catch (Exception e) {
            log.warn("Token yeniden şifreleme başarısız: {}", e.getClass().getSimpleName());
        }
    }

    public Result run() {
        String prefix = "v2:" + crypto.currentKeyId() + ":";
        long afterId = 0;
        int ok = 0;
        int failed = 0;
        List<StoredToken> batch;
        do {
            batch = users.findTokensWithoutPrefix(prefix, afterId, BATCH_SIZE);
            for (StoredToken row : batch) {
                afterId = row.userId();
                String replacement;
                try {
                    replacement = crypto.reencrypt(row.githubToken());
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Token çözülemedi, atlandı (anahtar listede yok mu?). userId={}", row.userId());
                    continue;
                }
                if (users.replaceGithubToken(row.userId(), row.githubToken(), replacement)) {
                    ok++;
                }
            }
        } while (batch.size() == BATCH_SIZE);

        metrics.reencrypted("ok", ok);
        metrics.reencrypted("failed", failed);
        if (ok + failed > 0) {
            log.info("Anahtar dönüşü: {} token yeniden şifrelendi, {} çözülemedi (kid={})", ok, failed, crypto.currentKeyId());
        }
        return new Result(ok, failed);
    }
}
