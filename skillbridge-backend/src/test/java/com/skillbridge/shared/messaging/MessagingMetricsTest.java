package com.skillbridge.shared.messaging;

import com.skillbridge.common.config.RabbitMQConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The backlog gauges: what they read, and what they say when they cannot read.
 *
 * <p>The second half is the point. A gauge that reports zero, or its last value,
 * when the database or the broker is unreachable says "no backlog" at exactly the
 * moment nobody can know, and every alert built on it stays quiet.
 */
class MessagingMetricsTest {

    private SimpleMeterRegistry meters;
    private JdbcTemplate jdbc;
    private AmqpAdmin admin;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        jdbc = mock(JdbcTemplate.class);
        admin = mock(AmqpAdmin.class);
    }

    @Test
    @DisplayName("before the first refresh every gauge reads NaN, not zero")
    void nanUntilRead() {
        metrics(true);
        assertThat(gauge(MessagingMetrics.OUTBOX_EVENTS, "status", "pending")).isNaN();
        assertThat(gauge(MessagingMetrics.QUEUE_MESSAGES, "queue", RabbitMQConfig.DEAD_LETTER_QUEUE)).isNaN();
    }

    @Test
    @DisplayName("a refresh reads the counts and the queue depths")
    void reads() {
        when(jdbc.queryForObject(contains("outbox_events WHERE status = 'PENDING'"), eq(Long.class))).thenReturn(12L);
        when(jdbc.queryForObject(contains("outbox_events WHERE status = 'DEAD'"), eq(Long.class))).thenReturn(1L);
        when(jdbc.queryForObject(contains("dead_letter_events"), eq(Long.class))).thenReturn(3L);
        when(admin.getQueueInfo(RabbitMQConfig.DEAD_LETTER_QUEUE))
                .thenReturn(new QueueInformation(RabbitMQConfig.DEAD_LETTER_QUEUE, 4, 1));
        when(admin.getQueueInfo(RabbitMQConfig.AI_ANALYSIS_QUEUE))
                .thenReturn(new QueueInformation(RabbitMQConfig.AI_ANALYSIS_QUEUE, 250, 1));

        metrics(true).refresh();

        assertThat(gauge(MessagingMetrics.OUTBOX_EVENTS, "status", "pending")).isEqualTo(12.0);
        assertThat(gauge(MessagingMetrics.OUTBOX_EVENTS, "status", "dead")).isEqualTo(1.0);
        assertThat(gauge(MessagingMetrics.DEAD_LETTER_EVENTS, "status", "pending")).isEqualTo(3.0);
        assertThat(gauge(MessagingMetrics.QUEUE_MESSAGES, "queue", RabbitMQConfig.DEAD_LETTER_QUEUE)).isEqualTo(4.0);
        assertThat(gauge(MessagingMetrics.QUEUE_MESSAGES, "queue", RabbitMQConfig.AI_ANALYSIS_QUEUE)).isEqualTo(250.0);
    }

    @Test
    @DisplayName("an unreachable source reads NaN -- not zero, and not the last value -- until it recovers")
    void nanWhileUnreadable() {
        MessagingMetrics metrics = metrics(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
        when(admin.getQueueInfo(anyString())).thenReturn(new QueueInformation("q", 9, 0));
        metrics.refresh();
        assertThat(gauge(MessagingMetrics.OUTBOX_EVENTS, "status", "pending")).isEqualTo(5.0);

        when(jdbc.queryForObject(anyString(), eq(Long.class)))
                .thenThrow(new DataAccessResourceFailureException("database down"));
        when(admin.getQueueInfo(anyString()))
                .thenThrow(new AmqpConnectException(new ConnectException("broker down")));
        metrics.refresh();
        assertThat(gauge(MessagingMetrics.OUTBOX_EVENTS, "status", "pending")).isNaN();
        assertThat(gauge(MessagingMetrics.DEAD_LETTER_EVENTS, "status", "pending")).isNaN();
        assertThat(gauge(MessagingMetrics.QUEUE_MESSAGES, "queue", RabbitMQConfig.AI_ANALYSIS_QUEUE)).isNaN();

        org.mockito.Mockito.reset(jdbc, admin);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenReturn(7L);
        when(admin.getQueueInfo(anyString())).thenReturn(null);   // the queue does not exist
        metrics.refresh();
        assertThat(gauge(MessagingMetrics.OUTBOX_EVENTS, "status", "pending")).isEqualTo(7.0);
        assertThat(gauge(MessagingMetrics.QUEUE_MESSAGES, "queue", RabbitMQConfig.AI_ANALYSIS_QUEUE))
                .as("a missing queue is not an empty one").isNaN();
    }

    @Test
    @DisplayName("disabled, the scheduled refresh touches nothing")
    void disabled() {
        metrics(false).refreshOnSchedule();
        verifyNoInteractions(jdbc, admin);
    }

    @Test
    @DisplayName("every counter series exists at zero before its first increment")
    void countersPreRegistered() {
        MessagingMetrics metrics = metrics(true);
        for (String outcome : new String[]{"new", "duplicate"}) {
            for (String reason : MessagingMetrics.REASONS) {
                assertThat(meters.get(MessagingMetrics.DEAD_LETTER_RECORDED)
                        .tag("outcome", outcome).tag("reason", reason).counter().count()).isZero();
            }
        }
        assertThat(meters.get(MessagingMetrics.DEAD_LETTER_RECORD_FAILURES).counter().count()).isZero();

        metrics.deadLetterRecorded(true, "something the broker invented");
        assertThat(meters.get(MessagingMetrics.DEAD_LETTER_RECORDED)
                .tag("outcome", "new").tag("reason", "other").counter().count())
                .as("an unknown reason is bucketed, so a hostile header cannot mint series").isEqualTo(1.0);
    }

    private MessagingMetrics metrics(boolean enabled) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("jdbc", jdbc);
        beans.registerSingleton("admin", admin);
        return new MessagingMetrics(meters, beans.getBeanProvider(JdbcTemplate.class),
                beans.getBeanProvider(AmqpAdmin.class), enabled);
    }

    private double gauge(String name, String tag, String value) {
        return meters.get(name).tag(tag, value).gauge().value();
    }
}
