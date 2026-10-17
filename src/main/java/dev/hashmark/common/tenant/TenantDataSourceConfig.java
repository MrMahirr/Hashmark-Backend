package dev.hashmark.common.tenant;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Boot'un kurduğu uygulama havuzunu (Hikari) {@link TenantAwareDataSource} ile sarar; JdbcTemplate ve
 * transaction yöneticisi sarılmış olanı kullanır. Flyway kendi bağlantısıyla çalıştığı için etkilenmez.
 * Hikari metrikleri ve sağlık göstergesi {@code unwrap} ile havuza ulaşmaya devam eder.
 */
@Configuration(proxyBeanMethods = false)
public class TenantDataSourceConfig {

    @Bean
    static BeanPostProcessor tenantAwareDataSourcePostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && "dataSource".equals(beanName)
                        && !(bean instanceof TenantAwareDataSource)) {
                    return new TenantAwareDataSource(ds);
                }
                return bean;
            }
        };
    }
}
