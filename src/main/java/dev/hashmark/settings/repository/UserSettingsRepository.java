package dev.hashmark.settings.repository;

import dev.hashmark.auth.model.User;
import dev.hashmark.settings.model.UserSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class UserSettingsRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserSettingsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<UserSettings> rowMapper = (rs, rowNum) -> UserSettings.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .emailNotify(rs.getBoolean("email_notify"))
            .notifyDay(rs.getString("notify_day"))
            .digestLabels(rs.getString("digest_labels"))
            .digestEmail(rs.getString("digest_email"))
            .hourlyRate(rs.getBigDecimal("hourly_rate"))
            .currency(rs.getString("currency"))
            .lastDigestSentOn(rs.getDate("last_digest_sent_on") != null ? rs.getDate("last_digest_sent_on").toLocalDate() : null)
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    public Optional<UserSettings> findByUserId(Long userId) {
        String sql = "SELECT * FROM user_settings WHERE user_id = ?";
        return jdbcTemplate.query(sql, rowMapper, userId).stream().findFirst();
    }

    public UserSettings save(UserSettings settings) {
        String sql = "INSERT INTO user_settings (user_id, email_notify, notify_day, digest_labels, digest_email, " +
                     "hourly_rate, currency) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT (user_id) DO UPDATE " +
                     "SET email_notify = EXCLUDED.email_notify, notify_day = EXCLUDED.notify_day, " +
                     "digest_labels = EXCLUDED.digest_labels, digest_email = EXCLUDED.digest_email, " +
                     "hourly_rate = EXCLUDED.hourly_rate, currency = EXCLUDED.currency " +
                     "RETURNING *";
        return jdbcTemplate.queryForObject(
                sql,
                rowMapper,
                settings.getUserId(),
                settings.getEmailNotify(),
                settings.getNotifyDay(),
                settings.getDigestLabels(),
                settings.getDigestEmail(),
                settings.getHourlyRate(),
                settings.getCurrency()
        );
    }

    /** Bir kullanıcı ve ayarları. */
    public record DigestRecipient(User user, UserSettings settings) {
    }

    /**
     * Bugün özet isteyen kullanıcılar: özet açık, gönderim günü eşleşiyor ve bugün henüz gönderilmemiş.
     *
     * @param dayName {@code MONDAY} ... {@code SUNDAY}
     */
    public List<DigestRecipient> findDigestRecipients(String dayName, LocalDate today) {
        String sql = "SELECT u.id AS u_id, u.github_id, u.email, u.name, u.github_login, u.avatar_url, u.github_token, " +
                     "u.created_at AS u_created, us.* " +
                     "FROM users u JOIN user_settings us ON u.id = us.user_id " +
                     "WHERE us.email_notify = true AND upper(us.notify_day) = ? " +
                     "AND (us.last_digest_sent_on IS NULL OR us.last_digest_sent_on < ?)";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            User user = User.builder()
                    .id(rs.getLong("u_id"))
                    .githubId(rs.getString("github_id"))
                    .email(rs.getString("email"))
                    .name(rs.getString("name"))
                    .githubLogin(rs.getString("github_login"))
                    .avatarUrl(rs.getString("avatar_url"))
                    .githubToken(rs.getString("github_token"))
                    .createdAt(rs.getTimestamp("u_created").toLocalDateTime())
                    .build();
            return new DigestRecipient(user, rowMapper.mapRow(rs, rowNum));
        }, dayName.toUpperCase(), java.sql.Date.valueOf(today));
    }

    public void markDigestSent(Long userId, LocalDate day) {
        jdbcTemplate.update("UPDATE user_settings SET last_digest_sent_on = ? WHERE user_id = ?",
                java.sql.Date.valueOf(day), userId);
    }
}
