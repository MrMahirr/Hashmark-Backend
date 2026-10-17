package dev.hashmark.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Refresh token kayıtları (jti başına bir satır). Zamanlar veritabanı saatiyle yönetilir. */
@Repository
public class RefreshTokenRepository {

    /** Bir token'ın durumu; yeniden kullanım tespiti için gerekir. */
    public record TokenState(String familyId, boolean used, boolean revoked, long secondsSinceUsed) {}

    private final JdbcTemplate jdbc;

    public RefreshTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String jti, String familyId, Long userId, long ttlMillis) {
        jdbc.update("INSERT INTO refresh_tokens (jti, family_id, user_id, expires_at) " +
                        "VALUES (?, ?, ?, NOW() + (? * INTERVAL '1 millisecond'))",
                jti, familyId, userId, ttlMillis);
    }

    /** Token hâlâ kullanılabilirse tek hamlede "kullanıldı" işaretler (atomik). */
    public boolean markUsedIfActive(String jti) {
        return jdbc.update("UPDATE refresh_tokens SET used_at = NOW() " +
                "WHERE jti = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW()", jti) == 1;
    }

    public Optional<TokenState> findState(String jti) {
        List<TokenState> rows = jdbc.query(
                "SELECT family_id, used_at IS NOT NULL AS used, revoked_at IS NOT NULL AS revoked, " +
                        "COALESCE(EXTRACT(EPOCH FROM (NOW() - used_at)), 0)::bigint AS since_used " +
                        "FROM refresh_tokens WHERE jti = ?",
                (rs, i) -> new TokenState(rs.getString("family_id"), rs.getBoolean("used"),
                        rs.getBoolean("revoked"), rs.getLong("since_used")),
                jti);
        return rows.stream().findFirst();
    }

    public int revokeFamily(String familyId) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = NOW() " +
                "WHERE family_id = ? AND revoked_at IS NULL", familyId);
    }

    public int revokeAllForUser(Long userId) {
        return jdbc.update("UPDATE refresh_tokens SET revoked_at = NOW() " +
                "WHERE user_id = ? AND revoked_at IS NULL", userId);
    }

    public int deleteExpired() {
        // Süresi dolmuş kayıtlar bir gün daha tutulur (yeniden kullanım izi için).
        return jdbc.update("DELETE FROM refresh_tokens WHERE expires_at < NOW() - INTERVAL '1 day'");
    }
}
