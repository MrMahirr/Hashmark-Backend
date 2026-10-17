package dev.hashmark.consent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public class ConsentRepository {

    public static final String DATA_PROCESSING = "DATA_PROCESSING";

    public record ActiveConsent(int policyVersion, String language, String textHash, LocalDateTime acceptedAt) {}

    private final JdbcTemplate jdbc;

    public ConsentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ActiveConsent> findActive(long userId, String type) {
        return jdbc.query(
                "SELECT policy_version, language, text_hash, accepted_at FROM user_consents " +
                "WHERE user_id = ? AND type = ? AND withdrawn_at IS NULL",
                (rs, i) -> new ActiveConsent(rs.getInt("policy_version"), rs.getString("language"),
                        rs.getString("text_hash"), rs.getTimestamp("accepted_at").toLocalDateTime()),
                userId, type).stream().findFirst();
    }

    /** Önceki etkin onayı kapatır, yenisini yazar (tek transaction'da çağrılmalı). */
    public void replaceActive(long userId, String type, int version, String language, String textHash) {
        withdraw(userId, type);
        jdbc.update("INSERT INTO user_consents (user_id, type, policy_version, language, text_hash) VALUES (?, ?, ?, ?, ?)",
                userId, type, version, language, textHash);
    }

    /** @return geri çekilen etkin onay sayısı (0 veya 1) */
    public int withdraw(long userId, String type) {
        return jdbc.update("UPDATE user_consents SET withdrawn_at = NOW() WHERE user_id = ? AND type = ? AND withdrawn_at IS NULL",
                userId, type);
    }

    /** Özel depo ek onayı; yalnızca sahibinin deposunu işaretler. @return etkilenen satır */
    public int acknowledgePrivateRepo(long userId, long repoId) {
        return jdbc.update("UPDATE repos SET private_ack_at = NOW() WHERE id = ? AND user_id = ?", repoId, userId);
    }

    public Optional<Timestamp> privateAckAt(long repoId) {
        return jdbc.query("SELECT private_ack_at FROM repos WHERE id = ?", (rs, i) -> rs.getTimestamp(1), repoId)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }
}
