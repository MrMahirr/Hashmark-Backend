package dev.hashmark.auth.service;

import dev.hashmark.common.metrics.AppMetrics;
import dev.hashmark.auth.dto.GitHubUserDto;
import dev.hashmark.auth.dto.LoginResponse;
import dev.hashmark.auth.model.User;
import dev.hashmark.auth.repository.OAuthStateRepository;
import dev.hashmark.auth.repository.RefreshTokenRepository;
import dev.hashmark.auth.repository.UserRepository;
import dev.hashmark.common.exception.ApiException;
import dev.hashmark.common.util.AesEncryptionUtil;
import dev.hashmark.common.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.hashmark.common.ratelimit.AccountRateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** OAuth akışının tamamlanması için tanınan süre. */
    private static final long STATE_TTL_MS = 10 * 60 * 1000L;

    /** Aynı refresh token'ın bu süre içinde ikinci kez sunulması (çoklu sekme yarışı) saldırı sayılmaz. */
    private static final long REUSE_GRACE_SECONDS = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final GitHubOAuthService gitHubOAuthService;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;
    private final AesEncryptionUtil aesEncryptionUtil;
    private final RefreshTokenRepository refreshTokens;
    private final OAuthStateRepository oauthStates;
    private final AppMetrics metrics;
    private final AccountRateLimiter accountLimiter;

    @Value("${jwt.access-token-expiry}")
    private long accessTokenExpiry;

    public AuthService(GitHubOAuthService gitHubOAuthService, UserRepository userRepository, JwtUtil jwtUtil,
                       AesEncryptionUtil aesEncryptionUtil, RefreshTokenRepository refreshTokens,
                       OAuthStateRepository oauthStates, AppMetrics metrics) {
        this(gitHubOAuthService, userRepository, jwtUtil, aesEncryptionUtil, refreshTokens, oauthStates, metrics,
                AccountRateLimiter.disabled(metrics));
    }

    @Autowired
    public AuthService(GitHubOAuthService gitHubOAuthService, UserRepository userRepository, JwtUtil jwtUtil,
                       AesEncryptionUtil aesEncryptionUtil, RefreshTokenRepository refreshTokens,
                       OAuthStateRepository oauthStates, AppMetrics metrics, AccountRateLimiter accountLimiter) {
        this.accountLimiter = accountLimiter;
        this.metrics = metrics;
        this.gitHubOAuthService = gitHubOAuthService;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
        this.aesEncryptionUtil = aesEncryptionUtil;
        this.refreshTokens = refreshTokens;
        this.oauthStates = oauthStates;
    }

    /** Tek kullanımlık state üretir ve GitHub yönlendirme adresini döndürür. */
    public String initiateLogin() {
        String state = newState();
        oauthStates.insert(state, STATE_TTL_MS);
        return gitHubOAuthService.buildAuthorizationUrl(state);
    }

    public LoginResponse handleCallback(String code, String state) {
        if (state == null || state.isBlank() || !oauthStates.consume(state)) {
            throw ApiException.unauthorized("Invalid or expired OAuth state");
        }

        String githubToken = gitHubOAuthService.exchangeCodeForToken(code);
        GitHubUserDto userDto = gitHubOAuthService.fetchUserInfo(githubToken);
        // Hesap başına oturum açma sınırı (IP değiştiren otomasyon aynı hesaba sınırsız oturum açamaz)
        accountLimiter.checkLogin(userDto.getId());

        String encryptedToken = aesEncryptionUtil.encrypt(githubToken);

        User userToSave = User.builder()
                .githubId(userDto.getId())
                .email(userDto.getEmail())
                .name(userDto.getName() != null ? userDto.getName() : userDto.getLogin())
                .avatarUrl(userDto.getAvatarUrl())
                .githubLogin(userDto.getLogin())
                .githubToken(encryptedToken)
                .build();

        User savedUser = userRepository.save(userToSave);

        String accessToken = jwtUtil.generateAccessToken(savedUser.getId());
        String refreshToken = issueRefreshToken(savedUser.getId(), newId());

        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessTokenExpiry)
                .build();
    }

    /**
     * Refresh token rotation: sunulan token tek kullanımlıktır; yeni bir access + refresh çifti döner.
     * Daha önce kullanılmış bir token tekrar gelirse aile çalınmış sayılıp tamamen iptal edilir.
     */
    public LoginResponse refresh(String refreshToken) {
        JwtUtil.RefreshClaims claims = jwtUtil.parseRefreshToken(refreshToken)
                .orElseThrow(() -> ApiException.unauthorized("Invalid refresh token"));
        // Token tüketilmeden ÖNCE: sınıra takılan istemcinin token'ı yanmaz, Retry-After sonrası aynı token geçerli
        accountLimiter.checkRefresh(claims.userId());

        if (!refreshTokens.markUsedIfActive(claims.jti())) {
            refreshTokens.findState(claims.jti()).ifPresent(state -> {
                if (state.used() && !state.revoked() && state.secondsSinceUsed() > REUSE_GRACE_SECONDS) {
                    int revoked = refreshTokens.revokeFamily(state.familyId());
                    metrics.refreshReuseDetected();
                    log.warn("Refresh token yeniden kullanımı tespit edildi; kullanıcı={}, iptal edilen={}",
                            claims.userId(), revoked);
                }
            });
            throw ApiException.unauthorized("Refresh token is invalidated");
        }

        String newRefresh = issueRefreshToken(claims.userId(), claims.familyId());
        return LoginResponse.builder()
                .accessToken(jwtUtil.generateAccessToken(claims.userId()))
                .refreshToken(newRefresh)
                .expiresIn(accessTokenExpiry)
                .build();
    }

    /** Çıkış: token'ın ait olduğu oturum ailesi iptal edilir. */
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        jwtUtil.parseRefreshToken(refreshToken).ifPresent(claims -> refreshTokens.revokeFamily(claims.familyId()));
    }

    /** Kullanıcının tüm oturumlarını sonlandırır (hesap silme vb.). */
    public void revokeAllSessions(Long userId) {
        refreshTokens.revokeAllForUser(userId);
    }

    private String issueRefreshToken(Long userId, String familyId) {
        String jti = newId();
        refreshTokens.insert(jti, familyId, userId, jwtUtil.getRefreshTokenExpiryMs());
        return jwtUtil.generateRefreshToken(userId, jti, familyId);
    }

    private static String newState() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String newId() {
        return java.util.UUID.randomUUID().toString();
    }
}
