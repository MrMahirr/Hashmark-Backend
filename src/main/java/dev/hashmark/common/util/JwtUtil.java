package dev.hashmark.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtUtil {

    public static final String TYPE_CLAIM = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    private static final String FAMILY_CLAIM = "fam";

    /** Doğrulanmış bir refresh token'ın içeriği. */
    public record RefreshClaims(Long userId, String jti, String familyId, Date expiresAt) {}

    private final SecretKey secretKey;
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiry}") long accessTokenExpiry,
            @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    public String generateAccessToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TYPE_CLAIM, TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
                .signWith(secretKey)
                .compact();
    }

    /** Yeni bir oturum ailesi başlatan refresh token üretir. */
    public String generateRefreshToken(Long userId) {
        return generateRefreshToken(userId, UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    public String generateRefreshToken(Long userId, String jti, String familyId) {
        return Jwts.builder()
                .id(jti)
                .subject(String.valueOf(userId))
                .claim(TYPE_CLAIM, TYPE_REFRESH)
                .claim(FAMILY_CLAIM, familyId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshTokenExpiry))
                .signWith(secretKey)
                .compact();
    }

    public long getRefreshTokenExpiryMs() {
        return refreshTokenExpiry;
    }

    /** İmza ve süre geçerli mi (token türünden bağımsız). */
    public boolean validateToken(String token) {
        return parse(token).isPresent();
    }

    /** API isteklerinde yalnızca access token kabul edilir; refresh token ile yetkilendirme yapılamaz. */
    public boolean validateAccessToken(String token) {
        return parse(token)
                .map(c -> TYPE_ACCESS.equals(c.get(TYPE_CLAIM, String.class)))
                .orElse(false);
    }

    /** Geçerli bir refresh token'ın jti/family bilgisini döndürür; değilse boş. */
    public Optional<RefreshClaims> parseRefreshToken(String token) {
        return parse(token)
                .filter(c -> TYPE_REFRESH.equals(c.get(TYPE_CLAIM, String.class)))
                .filter(c -> c.getId() != null && c.get(FAMILY_CLAIM, String.class) != null)
                .map(c -> new RefreshClaims(
                        Long.parseLong(c.getSubject()),
                        c.getId(),
                        c.get(FAMILY_CLAIM, String.class),
                        c.getExpiration()));
    }

    public Long extractUserId(String token) {
        return parse(token)
                .map(c -> Long.parseLong(c.getSubject()))
                .orElseThrow(() -> new IllegalArgumentException("Invalid token"));
    }

    private Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload());
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
