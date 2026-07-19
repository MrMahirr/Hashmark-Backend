package dev.hashmark.settings.repository;

import dev.hashmark.auth.model.User;
import dev.hashmark.settings.model.UserSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    public Optional<UserSettings> findByUserId(Long userId) {
        String sql = "SELECT * FROM user_settings WHERE user_id = ?";
        return jdbcTemplate.query(sql, rowMapper, userId).stream().findFirst();
    }

    public UserSettings save(UserSettings settings) {
        String sql = "INSERT INTO user_settings (user_id, email_notify, notify_day) " +
                     "VALUES (?, ?, ?) " +
                     "ON CONFLICT (user_id) DO UPDATE " +
                     "SET email_notify = EXCLUDED.email_notify, notify_day = EXCLUDED.notify_day " +
                     "RETURNING *";
        return jdbcTemplate.queryForObject(
                sql,
                rowMapper,
                settings.getUserId(),
                settings.getEmailNotify(),
                settings.getNotifyDay()
        );
    }

    public List<User> findUsersWithNotifyEnabled() {
        String sql = "SELECT u.id, u.github_id, u.email, u.name, u.github_token, u.created_at " +
                     "FROM users u " +
                     "JOIN user_settings us ON u.id = us.user_id " +
                     "WHERE us.email_notify = true";
                     
        RowMapper<User> userRowMapper = (rs, rowNum) -> User.builder()
                .id(rs.getLong("id"))
                .githubId(rs.getString("github_id"))
                .email(rs.getString("email"))
                .name(rs.getString("name"))
                .githubToken(rs.getString("github_token"))
                .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                .build();
                
        return jdbcTemplate.query(sql, userRowMapper);
    }
}
