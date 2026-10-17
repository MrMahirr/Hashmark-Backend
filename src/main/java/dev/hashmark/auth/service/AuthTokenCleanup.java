package dev.hashmark.auth.service;

import dev.hashmark.auth.repository.OAuthStateRepository;
import dev.hashmark.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Süresi dolmuş refresh token ve OAuth state kayıtlarını temizler. */
@Component
public class AuthTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(AuthTokenCleanup.class);

    private final RefreshTokenRepository refreshTokens;
    private final OAuthStateRepository oauthStates;

    public AuthTokenCleanup(RefreshTokenRepository refreshTokens, OAuthStateRepository oauthStates) {
        this.refreshTokens = refreshTokens;
        this.oauthStates = oauthStates;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @SchedulerLock(name = "auth-token-cleanup", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void purge() {
        try {
            int tokens = refreshTokens.deleteExpired();
            int states = oauthStates.deleteExpired();
            if (tokens + states > 0) {
                log.info("Auth cleanup: {} refresh token, {} oauth state silindi", tokens, states);
            }
        } catch (Exception e) {
            log.warn("Auth cleanup başarısız: {}", e.getMessage());
        }
    }
}
