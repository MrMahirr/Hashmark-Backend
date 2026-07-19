package dev.hashmark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

@MockBean(JdbcTemplate.class)
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "github.client-id=test-client",
        "github.client-secret=test-secret",
        "github.callback-url=http://localhost/auth/callback",
        "jwt.secret=01234567890123456789012345678901",
        "jwt.access-token-expiry=900000",
        "jwt.refresh-token-expiry=604800000",
        "encryption.secret=01234567890123456789012345678901"
})
class HashmarkApplicationTests {

    @Test
    void contextLoads() {
        // Context loads without depending on a local PostgreSQL instance.
    }
}
