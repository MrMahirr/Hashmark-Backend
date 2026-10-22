package dev.hashmark.channel;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class NotificationChannelRepository {

    /** Kayıtlı kanal; {@code urlEncrypted} API yanıtına asla konmaz. */
    public record Channel(long id, long userId, ChannelKind kind, String urlEncrypted, String urlHint,
                          boolean sendDigest, boolean sendAlerts, String lastStatus, String lastError,
                          LocalDateTime lastSentAt, LocalDateTime createdAt) {
    }

    private static final RowMapper<Channel> MAPPER = (rs, i) -> new Channel(
            rs.getLong("id"), rs.getLong("user_id"), ChannelKind.valueOf(rs.getString("kind")),
            rs.getString("url_encrypted"), rs.getString("url_hint"), rs.getBoolean("send_digest"),
            rs.getBoolean("send_alerts"), rs.getString("last_status"), rs.getString("last_error"),
            ts(rs.getTimestamp("last_sent_at")), ts(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public NotificationChannelRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static LocalDateTime ts(Timestamp t) {
        return t != null ? t.toLocalDateTime() : null;
    }

    public List<Channel> findByUserId(long userId) {
        return jdbcTemplate.query("SELECT * FROM notification_channels WHERE user_id = ? ORDER BY id", MAPPER, userId);
    }

    public Optional<Channel> findByIdForUser(long id, long userId) {
        return jdbcTemplate.query("SELECT * FROM notification_channels WHERE id = ? AND user_id = ?", MAPPER, id, userId)
                .stream().findFirst();
    }

    public int countByUserId(long userId) {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_channels WHERE user_id = ?",
                Integer.class, userId);
        return n == null ? 0 : n;
    }

    public Channel insert(long userId, ChannelKind kind, String urlEncrypted, String urlHint, boolean sendDigest,
                          boolean sendAlerts) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO notification_channels (user_id, kind, url_encrypted, url_hint, send_digest, send_alerts) " +
                "VALUES (?, ?, ?, ?, ?, ?) RETURNING *",
                MAPPER, userId, kind.name(), urlEncrypted, urlHint, sendDigest, sendAlerts);
    }

    /** {@code null} alanlar değişmez. */
    public Optional<Channel> updateFlags(long id, long userId, Boolean sendDigest, Boolean sendAlerts) {
        return jdbcTemplate.query(
                "UPDATE notification_channels SET send_digest = COALESCE(?, send_digest), " +
                "send_alerts = COALESCE(?, send_alerts) WHERE id = ? AND user_id = ? RETURNING *",
                MAPPER, sendDigest, sendAlerts, id, userId).stream().findFirst();
    }

    public boolean delete(long id, long userId) {
        return jdbcTemplate.update("DELETE FROM notification_channels WHERE id = ? AND user_id = ?", id, userId) == 1;
    }

    public void recordResult(long id, boolean ok, String error) {
        jdbcTemplate.update(
                "UPDATE notification_channels SET last_status = ?, last_error = ?, last_sent_at = NOW() WHERE id = ?",
                ok ? "OK" : "FAILED", ok ? null : truncate(error), id);
    }

    private static String truncate(String value) {
        return value == null ? null : value.length() > 200 ? value.substring(0, 200) : value;
    }
}
