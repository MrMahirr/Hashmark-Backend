package dev.hashmark.consent;

import dev.hashmark.common.exception.ApiException;
import dev.hashmark.consent.ConsentRepository.ActiveConsent;
import dev.hashmark.repo.model.Repo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Onay kapısı (plan 5.10, K7). HTTP kapısı ({@link ConsentGateFilter}), tarama işçisi ve haftalık özet aynı
 * kararı buradan alır; arayüzü atlayan istek ya da arka plan işi onaysız veri işleyemez.
 *
 * <p>Her istekte DB'ye gitmemek için sonuç 30 sn önbelleklenir; kabul/geri çekme önbelleği hemen temizler.
 * Birden çok instance'ta (Faz 4) geri çekme diğer instance'a en geç 30 sn'de yansır.
 */
@Service
public class ConsentService {

    public static final String CONSENT_REQUIRED = "CONSENT_REQUIRED";
    public static final String POLICY_OUTDATED = "POLICY_OUTDATED";
    public static final String PRIVATE_REPO_ACK_REQUIRED = "PRIVATE_REPO_ACK_REQUIRED";
    static final long CACHE_MS = 30_000;

    public record Status(boolean enforced, boolean required, int currentVersion,
                         Integer acceptedVersion, LocalDateTime acceptedAt) {}

    private record Cached(boolean valid, long at) {}

    private final ConsentRepository consents;
    private final ConsentPolicyCatalog catalog;
    private final ConsentProperties props;
    private final TransactionTemplate tx;
    private final LongSupplier clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public ConsentService(ConsentRepository consents, ConsentPolicyCatalog catalog, ConsentProperties props,
                          PlatformTransactionManager transactionManager) {
        this(consents, catalog, props, new TransactionTemplate(transactionManager), System::currentTimeMillis);
    }

    ConsentService(ConsentRepository consents, ConsentPolicyCatalog catalog, ConsentProperties props,
                   TransactionTemplate tx, LongSupplier clock) {
        this.consents = consents;
        this.catalog = catalog;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    public boolean isEnforced() {
        return props.isEnforced();
    }

    /** Geçerli politika sürümüne etkin onay var mı (kapı kapalıyken de doğru yanıt verir). */
    public boolean hasValidConsent(long userId) {
        long now = clock.getAsLong();
        Cached c = cache.get(userId);
        if (c != null && now - c.at() < CACHE_MS) {
            return c.valid();
        }
        boolean valid = consents.findActive(userId, ConsentRepository.DATA_PROCESSING)
                .map(a -> a.policyVersion() == props.getPolicyVersion())
                .orElse(false);
        cache.put(userId, new Cached(valid, now));
        return valid;
    }

    /** Kapı açık ve onay yoksa veri işleme yasaktır. */
    public boolean isProcessingBlocked(long userId) {
        return props.isEnforced() && !hasValidConsent(userId);
    }

    public void requireForProcessing(long userId) {
        if (isProcessingBlocked(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, CONSENT_REQUIRED, "Consent is required before data is processed");
        }
    }

    public Status status(long userId) {
        Optional<ActiveConsent> active = consents.findActive(userId, ConsentRepository.DATA_PROCESSING);
        boolean valid = active.map(a -> a.policyVersion() == props.getPolicyVersion()).orElse(false);
        return new Status(props.isEnforced(), props.isEnforced() && !valid, props.getPolicyVersion(),
                active.map(ActiveConsent::policyVersion).orElse(null),
                active.map(ActiveConsent::acceptedAt).orElse(null));
    }

    public ConsentPolicyCatalog.Text currentPolicy(String language) {
        return catalog.policy(props.getPolicyVersion(), language);
    }

    public ConsentPolicyCatalog.Text currentPrivateRepoNotice(String language) {
        return catalog.privateRepo(props.getPrivateRepoVersion(), language);
    }

    /**
     * Kullanıcının gördüğü metnin sürümü ve özeti sunucudakiyle eşleşmeli: eski sekmede kalmış bir sayfa ya da
     * uydurma bir istek, gösterilmemiş bir metne onay kaydedemez.
     */
    public Status accept(long userId, int version, String language, String textHash) {
        ConsentPolicyCatalog.Text text = currentPolicy(language);
        if (version != props.getPolicyVersion() || textHash == null || !text.hash().equalsIgnoreCase(textHash)) {
            throw new ApiException(HttpStatus.CONFLICT, POLICY_OUTDATED, "The policy text has changed; reload and review it");
        }
        tx.executeWithoutResult(s -> consents.replaceActive(userId, ConsentRepository.DATA_PROCESSING,
                version, text.language(), text.hash()));
        cache.remove(userId);
        return status(userId);
    }

    public Status withdraw(long userId) {
        consents.withdraw(userId, ConsentRepository.DATA_PROCESSING);
        cache.remove(userId);
        return status(userId);
    }

    /** Kapı açıkken özel depo, kullanıcı ek uyarıyı onaylamadan taranmaz (K1). */
    public void requirePrivateRepoAck(Repo repo) {
        if (props.isEnforced() && Boolean.TRUE.equals(repo.getIsPrivate())
                && consents.privateAckAt(repo.getId()).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, PRIVATE_REPO_ACK_REQUIRED,
                    "Confirm the private repository notice before scanning");
        }
    }

    public void acknowledgePrivateRepo(long userId, long repoId) {
        if (consents.acknowledgePrivateRepo(userId, repoId) == 0) {
            throw ApiException.notFound("Repository not found");
        }
    }
}
