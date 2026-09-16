package com.skillbridge.shared.messaging;

import com.skillbridge.common.config.RabbitMQConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The numbers the messaging alerts are written against, and the only place they are named.
 *
 * <p>{@code ops/prometheus/messaging-alerts.yml} refers to these by their Prometheus
 * names, and {@code MessagingAlertRulesTest} fails the build if a rule names a
 * metric this class does not emit. An alert on a metric that was renamed is the
 * quietest failure monitoring has: the rule is valid, the expression matches
 * nothing, and it never fires.
 *
 * <h2>Gauges are refreshed, not computed on scrape</h2>
 *
 * <p>A gauge that runs a {@code COUNT(*)} whenever Prometheus asks would put a
 * database query on a path that runs every few seconds, from outside any
 * transaction, however many scrapers there are. Instead a scheduled refresh stores
 * the values and the gauges read them. When a source cannot be read the gauge
 * reports NaN rather than a stale number or zero — zero would say "no backlog" at
 * exactly the moment nobody can tell — and {@code MessagingMetricsUnavailable}
 * alerts on NaN.
 *
 * <h2>Counters exist before they count</h2>
 *
 * <p>{@code dead_letter.recorded} is registered for every outcome and reason at
 * startup. Prometheus's {@code increase()} needs a series to exist before it
 * changes; a counter that first appears already at 1 is an increase nobody sees,
 * and the first dead letter is the one most worth seeing.
 */
@Component
@Slf4j
public class MessagingMetrics {

    public static final String OUTBOX_EVENTS = "outbox.events";
    public static final String DEAD_LETTER_EVENTS = "dead_letter.events";
    public static final String QUEUE_MESSAGES = "messaging.queue.messages";
    public static final String DEAD_LETTER_RECORDED = "dead_letter.recorded";
    public static final String DEAD_LETTER_RECORD_FAILURES = "dead_letter.record.failures";

    /** Death reasons as counter tags. Anything else a broker reports is counted as {@code other}. */
    static final List<String> REASONS = List.of(
            "consumer", "rejected", "delivery_limit", "expired", "maxlen", "unknown", "other");
    private static final Set<String> KNOWN_REASONS = Set.copyOf(REASONS);

    private final MeterRegistry registry;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<AmqpAdmin> admin;
    private final boolean enabled;

    private final GaugeValue outboxPending = new GaugeValue();
    private final GaugeValue outboxDead = new GaugeValue();
    private final GaugeValue deadLettersPending = new GaugeValue();
    private final GaugeValue deadLetterQueueDepth = new GaugeValue();
    private final GaugeValue analysisQueueDepth = new GaugeValue();
    private final Counter recordFailures;
    private final Map<String, Boolean> failing = new ConcurrentHashMap<>();

    public MessagingMetrics(MeterRegistry registry,
                            ObjectProvider<JdbcTemplate> jdbc,
                            ObjectProvider<AmqpAdmin> admin,
                            @Value("${messaging.backlog-metrics.enabled:true}") boolean enabled) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.admin = admin;
        this.enabled = enabled;

        gauge(OUTBOX_EVENTS, "status", "pending", outboxPending,
                "Outbox events not yet confirmed by the broker");
        gauge(OUTBOX_EVENTS, "status", "dead", outboxDead,
                "Outbox events the relay gave up on; each needs a person");
        gauge(DEAD_LETTER_EVENTS, "status", "pending", deadLettersPending,
                "Dead letters waiting for a replay or discard decision");
        gauge(QUEUE_MESSAGES, "queue", RabbitMQConfig.DEAD_LETTER_QUEUE, deadLetterQueueDepth,
                "Ready messages in the queue");
        gauge(QUEUE_MESSAGES, "queue", RabbitMQConfig.AI_ANALYSIS_QUEUE, analysisQueueDepth,
                "Ready messages in the queue");

        for (String outcome : List.of("new", "duplicate")) {
            for (String reason : REASONS) {
                recorded(outcome, reason);
            }
        }
        this.recordFailures = Counter.builder(DEAD_LETTER_RECORD_FAILURES)
                .description("Failed attempts to store a dead letter; the recorder retries, holding the message")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${messaging.backlog-metrics.refresh-ms:30000}",
            initialDelayString = "${messaging.backlog-metrics.initial-delay-ms:5000}")
    public void refreshOnSchedule() {
        if (enabled) {
            refresh();
        }
    }

    /** Reads every source once. Each that fails reads NaN until it recovers. */
    public void refresh() {
        outboxPending.set(count("outbox pending",
                "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'"));
        outboxDead.set(count("outbox dead",
                "SELECT count(*) FROM outbox_events WHERE status = 'DEAD'"));
        deadLettersPending.set(count("dead letters pending",
                "SELECT count(*) FROM dead_letter_events WHERE status = 'PENDING'"));
        deadLetterQueueDepth.set(depth(RabbitMQConfig.DEAD_LETTER_QUEUE));
        analysisQueueDepth.set(depth(RabbitMQConfig.AI_ANALYSIS_QUEUE));
    }

    public void deadLetterRecorded(boolean inserted, String deathReason) {
        recorded(inserted ? "new" : "duplicate",
                KNOWN_REASONS.contains(deathReason) ? deathReason : "other").increment();
    }

    public void deadLetterRecordFailed() {
        recordFailures.increment();
    }

    private Counter recorded(String outcome, String reason) {
        return Counter.builder(DEAD_LETTER_RECORDED)
                .description("Messages taken from the DLQ, by whether they were new and why they died")
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(registry);
    }

    private void gauge(String name, String tagKey, String tagValue, GaugeValue value, String description) {
        Gauge.builder(name, value, GaugeValue::get)
                .tag(tagKey, tagValue)
                .description(description)
                .register(registry);
    }

    private double count(String source, String sql) {
        JdbcTemplate template = jdbc.getIfAvailable();
        if (template == null) {
            return Double.NaN;
        }
        try {
            Long n = template.queryForObject(sql, Long.class);
            recovered(source);
            return n != null ? n : Double.NaN;
        } catch (RuntimeException e) {
            failed(source, e);
            return Double.NaN;
        }
    }

    private double depth(String queue) {
        AmqpAdmin amqp = admin.getIfAvailable();
        if (amqp == null) {
            return Double.NaN;
        }
        try {
            QueueInformation info = amqp.getQueueInfo(queue);
            if (info == null) {
                failed("queue " + queue, new IllegalStateException("queue does not exist"));
                return Double.NaN;
            }
            recovered("queue " + queue);
            return info.getMessageCount();
        } catch (RuntimeException e) {
            failed("queue " + queue, e);
            return Double.NaN;
        }
    }

    /** Logged once when a source starts failing, not every 30 seconds while it stays down. */
    private void failed(String source, Exception e) {
        if (failing.put(source, Boolean.TRUE) == null) {
            log.warn("Messaging metric source '{}' is unreadable; its gauge reads NaN until it recovers: {}",
                    source, e.toString());
        }
    }

    private void recovered(String source) {
        if (failing.remove(source) != null) {
            log.info("Messaging metric source '{}' is readable again", source);
        }
    }

    /** A gauge's value, written by the refresh and read by the scrape. */
    static final class GaugeValue {
        private volatile double current = Double.NaN;

        double get() {
            return current;
        }

        void set(double value) {
            current = value;
        }
    }
}
