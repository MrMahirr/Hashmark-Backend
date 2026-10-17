package dev.hashmark.common.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Zamanlanmış işler için dağıtık kilit (ShedLock, {@code shedlock} tablosu, V13). 2+ instance'ta aynı iş (haftalık
 * özet, saklama, token temizliği, yeniden şifreleme) tek instance'ta çalışır. Tarama dağıtıcısı kilitlenmez: her
 * instance kuyruktan {@code SKIP LOCKED} ile kendi işini alır.
 *
 * <p>Zaman veritabanından okunur ({@code usingDbTime}): instance saatleri kaysa da kilit süreleri tutarlıdır.
 * {@code lockAtMostFor}: instance kilit tutarken çökerse kilit en geç bu süre sonra serbest kalır.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(jdbcTemplate)
                .usingDbTime()
                .build());
    }
}
