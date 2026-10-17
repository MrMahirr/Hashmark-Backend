package dev.hashmark.auth.service;

import dev.hashmark.common.metrics.AppMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import dev.hashmark.auth.dto.LoginResponse;
import dev.hashmark.auth.repository.OAuthStateRepository;
import dev.hashmark.auth.repository.RefreshTokenRepository;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    private final JwtUtil jwtUtil = new JwtUtil("thisisaverylongsecretkeythatneedstobeatleast256bitslong", 900000, 604800000);
    private final RefreshTokenRepository refreshTokens = mock(RefreshTokenRepository.class);
    private final OAuthStateRepository oauthStates = mock(OAuthStateRepository.class);
    private final GitHubOAuthService github = mock(GitHubOAuthService.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AppMetrics metrics = new AppMetrics(registry);
    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(github, mock(UserRepository.class), jwtUtil, mock(AesEncryptionUtil.class),
                refreshTokens, oauthStates, metrics);
        ReflectionTestUtils.setField(service, "accessTokenExpiry", 900000L);
    }

    @Test
    void refreshRotatesTokenAndKeepsFamily() {
        String old = jwtUtil.generateRefreshToken(7L, "jti-1", "fam-1");
        when(refreshTokens.markUsedIfActive("jti-1")).thenReturn(true);

        LoginResponse res = service.refresh(old);

        assertNotEquals(old, res.getRefreshToken());
        JwtUtil.RefreshClaims next = jwtUtil.parseRefreshToken(res.getRefreshToken()).orElseThrow();
        assertEquals("fam-1", next.familyId());
        assertNotEquals("jti-1", next.jti());
        assertEquals(7L, next.userId());
        assertTrue(jwtUtil.validateAccessToken(res.getAccessToken()));
        verify(refreshTokens).insert(eq(next.jti()), eq("fam-1"), eq(7L), anyLong());
    }

    @Test
    void accountRefreshLimitRejectsBeforeTheTokenIsConsumed() {
        dev.hashmark.common.ratelimit.RateLimitProperties props = new dev.hashmark.common.ratelimit.RateLimitProperties();
        props.setRefreshPerAccountPerMinute(1);
        AuthService limited = new AuthService(github, mock(UserRepository.class), jwtUtil, mock(AesEncryptionUtil.class),
                refreshTokens, oauthStates, metrics, new dev.hashmark.common.ratelimit.AccountRateLimiter(props, metrics));
        ReflectionTestUtils.setField(limited, "accessTokenExpiry", 900000L);
        when(refreshTokens.markUsedIfActive("jti-1")).thenReturn(true);
        limited.refresh(jwtUtil.generateRefreshToken(7L, "jti-1", "fam-1"));

        ApiException ex = assertThrows(ApiException.class,
                () -> limited.refresh(jwtUtil.generateRefreshToken(7L, "jti-2", "fam-1")));

        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, ex.getHttpStatus());
        verify(refreshTokens, never()).markUsedIfActive("jti-2");
    }

    @Test
    void reusedTokenOutsideGraceRevokesWholeFamily() {
        String old = jwtUtil.generateRefreshToken(7L, "jti-1", "fam-1");
        when(refreshTokens.markUsedIfActive("jti-1")).thenReturn(false);
        when(refreshTokens.findState("jti-1"))
                .thenReturn(Optional.of(new RefreshTokenRepository.TokenState("fam-1", true, false, 120)));

        assertThrows(ApiException.class, () -> service.refresh(old));
        verify(refreshTokens).revokeFamily("fam-1");
        org.junit.jupiter.api.Assertions.assertEquals(1.0, registry.counter("hashmark.auth.refresh.reuse.detected").count());
    }

    @Test
    void reuseWithinGraceDoesNotRevokeFamily() {
        String old = jwtUtil.generateRefreshToken(7L, "jti-1", "fam-1");
        when(refreshTokens.markUsedIfActive("jti-1")).thenReturn(false);
        when(refreshTokens.findState("jti-1"))
                .thenReturn(Optional.of(new RefreshTokenRepository.TokenState("fam-1", true, false, 2)));

        assertThrows(ApiException.class, () -> service.refresh(old));
        verify(refreshTokens, never()).revokeFamily(anyString());
        org.junit.jupiter.api.Assertions.assertEquals(0.0, registry.counter("hashmark.auth.refresh.reuse.detected").count());
    }

    @Test
    void accessTokenCannotBeUsedAsRefreshToken() {
        String access = jwtUtil.generateAccessToken(7L);
        assertThrows(ApiException.class, () -> service.refresh(access));
        verify(refreshTokens, never()).markUsedIfActive(anyString());
    }

    @Test
    void refreshTokenCannotAuthenticateApiRequests() {
        assertFalse(jwtUtil.validateAccessToken(jwtUtil.generateRefreshToken(7L)));
    }

    @Test
    void logoutRevokesFamily() {
        service.logout(jwtUtil.generateRefreshToken(7L, "jti-1", "fam-9"));
        verify(refreshTokens).revokeFamily("fam-9");
    }

    @Test
    void callbackRejectsUnknownOrMissingState() {
        when(oauthStates.consume("bad")).thenReturn(false);
        assertThrows(ApiException.class, () -> service.handleCallback("code", "bad"));
        assertThrows(ApiException.class, () -> service.handleCallback("code", null));
        verify(github, never()).exchangeCodeForToken(anyString());
    }

    @Test
    void initiateLoginStoresStateBeforeRedirect() {
        when(github.buildAuthorizationUrl(anyString())).thenReturn("https://github.com/x");
        assertEquals("https://github.com/x", service.initiateLogin());
        verify(oauthStates).insert(anyString(), anyLong());
    }
}
