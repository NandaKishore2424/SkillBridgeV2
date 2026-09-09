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

    /**
     * Carries AI events to RabbitMQ after the publishing transaction commits.
     *
     * <p>Deliberately small and bounded. The work is one AMQP publish; the
     * point of the executor is only to get the network call off the thread
     * that is still holding a database connection, not to add throughput.
     *
     * <p>The rejection policy is the default {@code AbortPolicy} rather than
     * {@code CallerRunsPolicy}: caller-runs would hand the publish back to the
     * committing thread, which is precisely the thread whose connection we are
     * trying to release. A rejected event is logged and dropped, which matches
     * what {@code AIEventPublisher} already does when the broker is down.
     */
    @Bean(name = "aiEventExecutor")
    public Executor aiEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ai-event-");
        executor.initialize();
        return executor;
    }

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
