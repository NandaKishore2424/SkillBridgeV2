package com.skillbridge.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async execution and scheduling.
 *
 * <p>{@code @EnableScheduling} is what makes {@code @Scheduled} methods run at
 * all — without it they are inert and fail silently, which is a genuinely
 * annoying thing to debug.
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "bulkUploadExecutor")
    public Executor bulkUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("bulk-upload-");
        executor.initialize();
        return executor;
    }
}
