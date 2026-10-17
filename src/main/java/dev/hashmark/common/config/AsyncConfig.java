package dev.hashmark.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Value("${async.core-pool-size:2}")
    private int corePoolSize;

    @Value("${async.max-pool-size:5}")
    private int maxPoolSize;

    @Value("${async.queue-capacity:50}")
    private int queueCapacity;

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("hashmark-async-");
        // Bilinçli seçim: kuyruk dolunca çağıranı bloklamak (CallerRunsPolicy) yerine reddet.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Kapanış: varsayılan davranış ContextClosedEvent'te çalışan taramaları HEMEN keser (erken kapanış).
        // Bunun yerine ScanJobDispatcher.stop() önce bekler, bitmeyenleri jetonla kuyruğa geri bırakır ve keser;
        // executor en son, kısa bir beklemeyle kapanır.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
