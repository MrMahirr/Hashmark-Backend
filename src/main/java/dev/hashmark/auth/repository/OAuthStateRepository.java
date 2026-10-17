package dev.hashmark.auth.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Tek kullanımlık OAuth state değerleri. */
@Repository
public class OAuthStateRepository {

    private final JdbcTemplate jdbc;

    public OAuthStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String state, long ttlMillis) {
        jdbc.update("INSERT INTO oauth_states (state, expires_at) VALUES (?, NOW() + (? * INTERVAL '1 millisecond'))",
                state, ttlMillis);
    }

    /** State varsa ve süresi dolmadıysa siler ve true döner; ikinci kullanım false verir. */
    public boolean consume(String state) {
        return jdbc.update("DELETE FROM oauth_states WHERE state = ? AND expires_at > NOW()", state) == 1;
    }

    public int deleteExpired() {
        return jdbc.update("DELETE FROM oauth_states WHERE expires_at < NOW()");
    }
}
