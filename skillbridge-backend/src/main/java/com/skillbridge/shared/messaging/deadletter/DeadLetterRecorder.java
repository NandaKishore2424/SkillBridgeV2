package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.shared.messaging.MessagingMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.function.LongConsumer;

/**
 * Empties {@code skillbridge.dlq} into {@code dead_letter_events}, one message at a time.
 *
 * <h2>Ack only what is stored</h2>
 *
 * <p>Manual acknowledgement: the ack goes out after the insert commits, never
 * before, so a message is never removed from the DLQ without a row to show for it.
 * Dying between the two redelivers it, and the insert's {@code ON CONFLICT} makes
 * the second attempt a no-op.
 *
 * <h2>When the database is down, wait — holding the message</h2>
 *
 * <p>The obvious alternative, nack with requeue, is wrong twice over. It spins: the
 * message comes straight back, fails again, and floods the log. And it counts: a
 * quorum queue tracks redeliveries, RabbitMQ 4 gives every quorum queue a default
 * delivery limit, and the DLQ has no dead-letter exchange of its own, so past the
 * limit the broker would delete the very messages this class exists to keep.
 *
 * <p>So a failed insert is retried here, with the message unacknowledged and a
 * backoff of up to 30 seconds, until it succeeds or the application stops. Prefetch
 * is one, so nothing else is held up behind it that was not already waiting. If the
 * failure is not the database but this code, it retries for ever and says so every
 * time — and the {@code DeadLetterQueueNotDraining} alert fires. That is the right
 * way for it to fail: loudly, and without losing anything.
 *
 * <p>Off under the test profile. {@code DeadLetterRecorderBrokerTest} turns it on
 * against a broker it controls.
 */
@Component
@ConditionalOnProperty(prefix = "dead-letters.recorder", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class DeadLetterRecorder {

    static final String LISTENER_ID = "dead-letter-recorder";
    private static final long MAX_BACKOFF_MS = 30_000;

    private final DeadLetterService deadLetters;
    private final ObjectMapper objectMapper;
    private final MessagingMetrics metrics;
    private final LongConsumer sleeper;
    private volatile boolean stopping;

    @Autowired
    public DeadLetterRecorder(DeadLetterService deadLetters, ObjectMapper objectMapper, MessagingMetrics metrics) {
        this(deadLetters, objectMapper, metrics, DeadLetterRecorder::sleep);
    }

    DeadLetterRecorder(DeadLetterService deadLetters, ObjectMapper objectMapper, MessagingMetrics metrics,
                       LongConsumer sleeper) {
        this.deadLetters = deadLetters;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.sleeper = sleeper;
    }

    @RabbitListener(id = LISTENER_ID, queues = RabbitMQConfig.DEAD_LETTER_QUEUE,
            containerFactory = ListenerConfig.FACTORY)
    public void onMessage(Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();

        DeadLetterMessage parsed;
        try {
            parsed = DeadLetterMessage.from(message, objectMapper, Instant.now());
        } catch (RuntimeException e) {
            // from() is written not to throw. If it does, keep the bytes anyway.
            log.error("Could not read dead letter {}; recording it as unreadable", message.getMessageProperties()
                    .getMessageId(), e);
            parsed = DeadLetterMessage.unreadable(message, e, Instant.now());
        }

        boolean inserted = recordPatiently(parsed);
        channel.basicAck(tag, false);

        metrics.deadLetterRecorded(inserted, parsed.deathReason());
        if (inserted) {
            // Every new dead letter is a decision someone has to make, so it is worth a WARN.
            log.warn("Dead letter recorded: event {} ({}) from {}: {} -- {}",
                    parsed.eventId(), parsed.eventType(), parsed.sourceQueue(), parsed.deathReason(),
                    parsed.failureReason());
        } else {
            log.info("Dead letter {} was already recorded; acknowledged the redelivery", parsed.fingerprint());
        }
    }

    /**
     * Inserts, retrying with backoff until it works.
     *
     * @throws IllegalStateException if the application stops first; the message is
     *                               then left unacknowledged, and the broker redelivers it
     */
    boolean recordPatiently(DeadLetterMessage parsed) {
        for (int attempt = 1; ; attempt++) {
            try {
                return deadLetters.record(parsed);
            } catch (RuntimeException e) {
                if (stopping || Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("stopping; dead letter " + parsed.fingerprint()
                            + " left in the queue for the next start", e);
                }
                long wait = backoffMs(attempt);
                log.error("Could not record dead letter {} (event {}), attempt {}; holding it and retrying in {}ms",
                        parsed.fingerprint(), parsed.eventId(), attempt, wait, e);
                metrics.deadLetterRecordFailed();
                sleeper.accept(wait);
            }
        }
    }

    static long backoffMs(int attempt) {
        return Math.min(MAX_BACKOFF_MS, 1_000L << Math.min(Math.max(attempt - 1, 0), 15));
    }

    @PreDestroy
    void stop() {
        stopping = true;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * The recorder's container: manual acks, one message at a time, one consumer.
     *
     * <p>Its own factory rather than Boot's default, so no {@code spring.rabbitmq.listener}
     * setting added for some future listener can quietly change how dead letters are
     * acknowledged.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "dead-letters.recorder", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class ListenerConfig {

        static final String FACTORY = "deadLetterListenerFactory";

        @Bean(FACTORY)
        SimpleRabbitListenerContainerFactory deadLetterListenerFactory(ConnectionFactory connectionFactory) {
            SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
            factory.setConnectionFactory(connectionFactory);
            factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
            factory.setPrefetchCount(1);
            factory.setConcurrentConsumers(1);
            factory.setMaxConcurrentConsumers(1);
            // Only reached if onMessage throws, which it does only when the application
            // is stopping; the broker then redelivers to the next instance or start.
            factory.setDefaultRequeueRejected(true);
            // A broker that has not got the topology yet is not a reason to fail startup.
            factory.setMissingQueuesFatal(false);
            return factory;
        }
    }
}
