package dev.hashmark.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JWT üretimi/doğrulaması. Anahtar dönüşü (plan Faz 2): token başlığına imzalayan anahtarın kimliği ({@code kid})
 * yazılır; doğrulama kid'e göre geçerli ya da önceki anahtarı seçer. Dönüş adımları: docs/KEY_ROTATION.md.
 */
@Component
public class JwtUtil {

    public static final String TYPE_CLAIM = "typ";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    private static final String FAMILY_CLAIM = "fam";

    /** Doğrulanmış bir refresh token'ın içeriği. */
    public record RefreshClaims(Long userId, String jti, String familyId, Date expiresAt) {}

    private final SecretKey secretKey;
    private final String keyId;
    /** kid → anahtar; geçerli anahtar ilk sırada. */
    private final Map<String, SecretKey> keysById = new LinkedHashMap<>();
    private final long accessTokenExpiry;
    private final long refreshTokenExpiry;

    public JwtUtil(String secret, long accessTokenExpiry, long refreshTokenExpiry) {
        this(secret, "", accessTokenExpiry, refreshTokenExpiry);
    }

    @Autowired
    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.previous-secrets:}") String previousSecrets,
            @Value("${jwt.access-token-expiry}") long accessTokenExpiry,
            @Value("${jwt.refresh-token-expiry}") long refreshTokenExpiry) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.keyId = KeyIds.of(secret);
        keysById.put(keyId, secretKey);
        for (String previous : KeyIds.splitSecrets(previousSecrets)) {
            keysById.putIfAbsent(KeyIds.of(previous), Keys.hmacShaKeyFor(previous.getBytes(StandardCharsets.UTF_8)));
        }
        this.accessTokenExpiry = accessTokenExpiry;
        this.refreshTokenExpiry = refreshTokenExpiry;
    }

    /** İmzalayan (geçerli) anahtarın kimliği. */
    public String currentKeyId() {
        return keyId;
    }

    public String generateAccessToken(Long userId) {
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(TYPE_CLAIM, TYPE_ACCESS)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiry))
                .header().keyId(keyId).and()
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
                .header().keyId(keyId).and()
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
            return Optional.of(Jwts.parser().keyLocator(keyLocator).build().parseSignedClaims(token).getPayload());
        } catch (Exception e) {
            // kid'siz eski token'lar (dönüş desteğinden önce üretilmiş) önceki anahtarlarla da denenir
            for (SecretKey previous : previousKeys()) {
                try {
                    return Optional.of(Jwts.parser().verifyWith(previous).build().parseSignedClaims(token).getPayload());
                } catch (Exception ignored) {
                    // sıradaki anahtar
                }
            }
            return Optional.empty();
        }
    }

    private List<SecretKey> previousKeys() {
        return keysById.values().stream().skip(1).toList();
    }

    /** kid varsa yalnızca o anahtar (bilinmeyen kid → reddedilir); yoksa geçerli anahtar. */
    private final LocatorAdapter<Key> keyLocator = new LocatorAdapter<>() {
        @Override
        protected Key locate(ProtectedHeader header) {
            String kid = header.getKeyId();
            if (kid == null) {
                return secretKey;
            }
            SecretKey key = keysById.get(kid);
            if (key == null) {
                throw new io.jsonwebtoken.security.SecurityException("Unknown key id");
            }
            return key;
        }

        @Override
        protected Key doLocate(Header header) {
            throw new io.jsonwebtoken.UnsupportedJwtException("Unsigned tokens are not accepted");
        }
    };
}
