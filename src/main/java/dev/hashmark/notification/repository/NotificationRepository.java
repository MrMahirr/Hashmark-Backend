package dev.hashmark.notification.repository;

import dev.hashmark.notification.model.Notification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class NotificationRepository {

    private final JdbcTemplate jdbcTemplate;

    public NotificationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Notification> rowMapper = (rs, rowNum) -> Notification.builder()
            .id(rs.getLong("id"))
            .userId(rs.getLong("user_id"))
            .title(rs.getString("title"))
            .description(rs.getString("description"))
            .type(rs.getString("type"))
            .isRead(rs.getBoolean("is_read"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    public List<Notification> findByUserIdOrderByCreatedAtDesc(Long userId, int limit) {
        String sql = "SELECT * FROM notifications WHERE user_id = ? ORDER BY created_at DESC LIMIT ?";
        return jdbcTemplate.query(sql, rowMapper, userId, limit);
    }

    public void insert(Notification notification) {
        String sql = "INSERT INTO notifications (user_id, title, description, type, is_read, created_at) VALUES (?, ?, ?, ?, ?, COALESCE(?, CURRENT_TIMESTAMP))";
        jdbcTemplate.update(sql, notification.getUserId(), notification.getTitle(), notification.getDescription(), notification.getType(), notification.isRead(), notification.getCreatedAt());
    }

    public void markAsRead(Long id, Long userId) {
        String sql = "UPDATE notifications SET is_read = TRUE WHERE id = ? AND user_id = ?";
        jdbcTemplate.update(sql, id, userId);
    }

    /** Saklama: süresi dolmuş okunmuş bildirimleri siler. @return silinen satır (en fazla {@code limit}) */
    public int deleteReadOlderThan(int days, int limit) {
        return jdbcTemplate.update(
                "DELETE FROM notifications WHERE id IN (SELECT id FROM notifications WHERE is_read " +
                "AND created_at < NOW() - (? * INTERVAL '1 day') ORDER BY id LIMIT ?)", days, limit);
    }

    public void markAllAsRead(Long userId) {
        String sql = "UPDATE notifications SET is_read = TRUE WHERE user_id = ?";
        jdbcTemplate.update(sql, userId);
    }
}
