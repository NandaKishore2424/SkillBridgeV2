package com.skillbridge.shared.messaging.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.common.scheduling.SingleRunGuard;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Delivers committed outbox events to RabbitMQ, at least once.
 *
 * <h2>The shape of one run</h2>
 *
 * <ol>
 *   <li>{@link OutboxStore#claimDue} — a short transaction: lock due rows with
 *       {@code SKIP LOCKED}, lease them.</li>
 *   <li>Publish each, with <b>no transaction open</b>, and wait for the broker to
 *       confirm it.</li>
 *   <li>{@link OutboxStore#recordPublished} or {@link OutboxStore#recordFailure}
 *       — another short transaction.</li>
 * </ol>
 *
 * <p>The phase document puts the publish inside the locking transaction. That
 * holds a database connection across a network round trip, which this codebase
 * already forbids ({@code ConnectionHoldingRulesTest}) because it is the usual
 * cause of pool exhaustion. The lease is what makes splitting it safe.
 *
 * <p>No advisory lock either. {@code SKIP LOCKED} already lets several relays run
 * at once on disjoint rows, and {@code SingleRunGuard} runs its job inside a
 * transaction, so publishing under it would hold exactly the connection this
 * design exists to release.
 *
 * <h2>At least once, not exactly once</h2>
 *
 * <p>A relay that publishes and dies before recording it publishes again when the
 * lease runs out. That is the guarantee on offer from any broker. The message
 * carries the event id so the consumer can deduplicate.
 */
@Component
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OutboxRelay {

    private final OutboxStore store;
    private final SingleRunGuard singleRun;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final RabbitTemplate template;

    private final int batchSize;
    private final Duration lease;
    private final int maxAttempts;
    private final long confirmTimeoutMs;
    private final int retentionDays;

    public OutboxRelay(OutboxStore store, SingleRunGuard singleRun, ObjectMapper objectMapper,
                       MeterRegistry meters, ConnectionFactory connectionFactory,
                       @Value("${outbox.relay.batch-size:100}") int batchSize,
                       @Value("${outbox.relay.lease-ms:60000}") long leaseMs,
                       @Value("${outbox.relay.max-attempts:8}") int maxAttempts,
                       @Value("${outbox.relay.confirm-timeout-ms:5000}") long confirmTimeoutMs,
                       @Value("${outbox.retention-days:7}") int retentionDays) {
        this.store = store;
        this.singleRun = singleRun;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.batchSize = batchSize;
        this.lease = Duration.ofMillis(leaseMs);
        this.maxAttempts = maxAttempts;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.retentionDays = retentionDays;

        // A template of its own, so mandatory is set here without changing the
        // shared bean every other part of the application gets.
        this.template = new RabbitTemplate(connectionFactory);
        this.template.setMandatory(true);
    }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
    public void poll() {
        try {
            relayOnce();
        } catch (Exception e) {
            // Spring's scheduler swallows a thrown exception without logging it,
            // so a relay failing every half second would otherwise be silent.
            log.error("Outbox relay run failed", e);
        }
    }

    /**
     * One claim-publish-record cycle. Public so tests drive it deterministically.
     *
     * @return how many events the broker confirmed this run
     */
    public int relayOnce() {
        List<ClaimedEvent> batch = store.claimDue(batchSize, lease, maxAttempts);
        int confirmed = 0;
        for (ClaimedEvent event : batch) {
            try {
                publish(event);
                store.recordPublished(event.id());
                // By schema version too: whether anything still goes out in an old
                // version is the question before a consumer can drop support for it.
                meters.counter("outbox.published", "eventType", event.eventType(),
                        "schemaVersion", String.valueOf(event.schemaVersion())).increment();
                confirmed++;
            } catch (Exception e) {
                Duration retryIn = OutboxBackoff.delayAfter(event.attempts(), ThreadLocalRandom.current().nextDouble());
                boolean dead = store.recordFailure(event, describe(e), maxAttempts, retryIn);
                if (dead) {
                    meters.counter("outbox.dead", "eventType", event.eventType()).increment();
                    log.error("Outbox event {} ({}) is DEAD after {} attempts: {}",
                            event.eventId(), event.eventType(), event.attempts(), describe(e));
                } else {
                    meters.counter("outbox.retried", "eventType", event.eventType()).increment();
                    log.warn("Outbox event {} ({}) failed on attempt {}/{}, retrying in {}ms: {}",
                            event.eventId(), event.eventType(), event.attempts(), maxAttempts,
                            retryIn.toMillis(), describe(e));
                }
            }
        }
        return confirmed;
    }

    /**
     * Publishes one event and returns only once the broker has accepted AND routed it.
     *
     * <h2>Why a CorrelationData, and what the first version got wrong</h2>
     *
     * <p>The first version used SIMPLE confirms: send, {@code waitForConfirmsOrDie},
     * then look in a map a returns callback filled. Its comment asserted that the
     * broker's basic.return arrives before basic.ack, "so by the time the confirm
     * has come back the returns callback has already run". On the wire that
     * ordering holds; in the client it does not -- the confirm wait and the
     * returns callback are delivered separately, and the check lost the race.
     * OutboxRelayBrokerTest caught it against a real RabbitMQ: an event routed to
     * no queue was marked PUBLISHED. That is silent data loss in the one class
     * whose whole job is preventing it, and a mocked template would never have
     * shown it, because a mock confirms whatever it is told to.
     *
     * <p>A {@link CorrelationData} carries both answers on one object: its future
     * completes with the ack or nack, and {@code getReturned()} holds the returned
     * message if the broker could not route it. Nothing to correlate by hand, and
     * no second channel of news to wait for.
     */
    private void publish(ClaimedEvent event) throws Exception {
        String messageId = event.eventId().toString();
        CorrelationData correlation = new CorrelationData(messageId);

        template.send(RabbitMQConfig.EVENTS_EXCHANGE, event.routingKey(), toMessage(event, messageId), correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new AmqpException("broker did not confirm the event within " + confirmTimeoutMs + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("interrupted while waiting for the broker to confirm the event", e);
        }

        if (!confirm.isAck()) {
            throw new AmqpException("broker nacked the event: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new AmqpException("broker returned the event as unroutable ("
                    + correlation.getReturned().getReplyCode() + " " + correlation.getReturned().getReplyText()
                    + "): exchange " + RabbitMQConfig.EVENTS_EXCHANGE + ", routing key " + event.routingKey());
        }
    }

    /**
     * The stored payload is sent as-is.
     *
     * <p>It is already JSON text. Handing the String to a JSON message converter
     * would serialise it a second time into a quoted string, and every consumer
     * would receive {@code "{\"eventType\":...}"} instead of an object.
     */
    private Message toMessage(ClaimedEvent event, String messageId) {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setMessageId(messageId);
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setHeader("eventId", messageId);
        props.setHeader("eventType", event.eventType());
        props.setHeader("schemaVersion", event.schemaVersion());
        storedHeaders(event).forEach(props::setHeader);
        return new Message(event.payload().getBytes(StandardCharsets.UTF_8), props);
    }

    private Map<String, String> storedHeaders(ClaimedEvent event) {
        if (event.headers() == null || event.headers().isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(event.headers(), new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            // Headers are context, not content. Losing them costs tracing, not the event.
            log.warn("Outbox event {} has unreadable headers; publishing without them", event.eventId());
            return Map.of();
        }
    }

    /**
     * Removes PUBLISHED rows past {@code outbox.retention-days}.
     *
     * <p>Under {@link SingleRunGuard} like the other nightly jobs. Unlike the relay
     * itself this is pure database work, so running it inside the guard's
     * transaction holds no connection across a network call.
     */
    @Scheduled(cron = "0 15 3 * * *")
    public void purge() {
        try {
            singleRun.runExclusively("outbox-purge", () -> {
                int deleted = store.purgePublishedBefore(LocalDateTime.now().minusDays(retentionDays));
                log.info("Purged {} published outbox events older than {} days", deleted, retentionDays);
            });
        } catch (Exception e) {
            log.error("Outbox purge failed", e);
        }
    }

    private static String describe(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
        return root == e ? message : e.getClass().getSimpleName() + ": " + message;
    }
}
