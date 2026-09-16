package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.skillbridge.shared.messaging.MessagingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The recorder's acknowledgement policy, with the database and the channel mocked.
 *
 * <p>The property under test is the one that loses data if it is wrong: a message
 * leaves the DLQ only after its row is stored, and a storage failure neither drops
 * it nor puts it back to spin. {@code DeadLetterRecorderBrokerTest} runs the same
 * recorder against a real broker and a real database.
 */
class DeadLetterRecorderTest {

    private static final long TAG = 77L;

    private DeadLetterService service;
    private Channel channel;
    private SimpleMeterRegistry meters;
    private List<Long> sleeps;
    private DeadLetterRecorder recorder;

    @BeforeEach
    void setUp() {
        service = mock(DeadLetterService.class);
        channel = mock(Channel.class);
        meters = new SimpleMeterRegistry();
        sleeps = new ArrayList<>();
        DefaultListableBeanFactory none = new DefaultListableBeanFactory();
        MessagingMetrics metrics = new MessagingMetrics(meters, none.getBeanProvider(JdbcTemplate.class),
                none.getBeanProvider(AmqpAdmin.class), false);
        recorder = new DeadLetterRecorder(service, new ObjectMapper(), metrics, sleeps::add);
    }

    @Test
    @DisplayName("acks after the row is stored, and counts it as new")
    void acksAfterStoring() throws Exception {
        when(service.record(any())).thenReturn(true);

        recorder.onMessage(message(), channel);

        var order = org.mockito.Mockito.inOrder(service, channel);
        order.verify(service).record(any());
        order.verify(channel).basicAck(TAG, false);
        assertThat(recorded("new")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a message already recorded is acked, and counted as a duplicate")
    void acksDuplicates() throws Exception {
        when(service.record(any())).thenReturn(false);

        recorder.onMessage(message(), channel);

        verify(channel).basicAck(TAG, false);
        assertThat(recorded("duplicate")).isEqualTo(1.0);
        assertThat(recorded("new")).isZero();
    }

    @Test
    @DisplayName("while the database fails it holds the message and retries with backoff -- no nack, no ack")
    void holdsAndRetries() throws Exception {
        when(service.record(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(true);

        recorder.onMessage(message(), channel);

        assertThat(sleeps).containsExactly(1_000L, 2_000L, 4_000L);
        verify(service, times(4)).record(any());
        verify(channel, times(1)).basicAck(TAG, false);
        // A requeue would spin, and count toward a quorum queue's delivery limit.
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
        assertThat(meters.get(MessagingMetrics.DEAD_LETTER_RECORD_FAILURES).counter().count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("stopping mid-retry leaves the message unacknowledged, for the broker to redeliver")
    void stopsWithoutAcking() throws Exception {
        when(service.record(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        recorder.stop();

        assertThatThrownBy(() -> recorder.onMessage(message(), channel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopping");
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("a message the parser chokes on is still stored, as unreadable, and acked")
    void unreadableIsStillStored() throws Exception {
        Message message = mock(Message.class);
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        when(message.getMessageProperties()).thenReturn(props);
        when(message.getBody())
                .thenThrow(new IllegalStateException("parser bug"))
                .thenReturn(new byte[]{1, 2});
        when(service.record(any())).thenReturn(true);

        recorder.onMessage(message, channel);

        ArgumentCaptor<DeadLetterMessage> stored = ArgumentCaptor.forClass(DeadLetterMessage.class);
        verify(service).record(stored.capture());
        assertThat(stored.getValue().failureReason()).contains("parser bug");
        assertThat(stored.getValue().payloadEncoding()).isEqualTo(DeadLetterEvent.BASE64);
        verify(channel).basicAck(TAG, false);
    }

    @Test
    @DisplayName("the backoff doubles from one second and stops at thirty")
    void backoff() {
        assertThat(List.of(1, 2, 3, 4, 5, 6, 7, 50, 0).stream().map(DeadLetterRecorder::backoffMs).toList())
                .containsExactly(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 1_000L);
    }

    private double recorded(String outcome) {
        return meters.get(MessagingMetrics.DEAD_LETTER_RECORDED)
                .tag("outcome", outcome).tag("reason", "unknown").counter().count();
    }

    private static Message message() {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        return new Message("{\"eventType\":\"SKILL_UPDATED\"}".getBytes(StandardCharsets.UTF_8), props);
    }
}
