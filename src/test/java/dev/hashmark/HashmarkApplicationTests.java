package dev.hashmark;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

// DB'siz duman testi: veri erişimi ve dağıtık kilit (ShedLock, DB tablosu) sahte
@MockitoBean(types = {JdbcTemplate.class, PlatformTransactionManager.class, net.javacrumbs.shedlock.core.LockProvider.class})
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
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
