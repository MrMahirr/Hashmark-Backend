package dev.hashmark.auth.repository;

import dev.hashmark.auth.model.User;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepository {

    private final JdbcTemplate jdbcTemplate;

    public UserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<User> userRowMapper = (rs, rowNum) -> User.builder()
            .id(rs.getLong("id"))
            .githubId(rs.getString("github_id"))
            .email(rs.getString("email"))
            .name(rs.getString("name"))
            .githubToken(rs.getString("github_token"))
            .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
            .build();

    public Optional<User> findByGithubId(String githubId) {
        String sql = "SELECT * FROM users WHERE github_id = ?";
        return jdbcTemplate.query(sql, userRowMapper, githubId).stream().findFirst();
    }

    public Optional<User> findById(Long id) {
        String sql = "SELECT * FROM users WHERE id = ?";
        return jdbcTemplate.query(sql, userRowMapper, id).stream().findFirst();
    }

    public List<User> findAll() {
        String sql = "SELECT * FROM users";
        return jdbcTemplate.query(sql, userRowMapper);
    }

    public User save(User user) {
        String sql = "INSERT INTO users (github_id, email, name, github_token) " +
                     "VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT (github_id) DO UPDATE " +
                     "SET email = EXCLUDED.email, name = EXCLUDED.name, github_token = EXCLUDED.github_token " +
                     "RETURNING *";
                     
        return jdbcTemplate.queryForObject(
                sql,
                userRowMapper,
                user.getGithubId(),
                user.getEmail(),
                user.getName(),
                user.getGithubToken()
        );
    }
}
