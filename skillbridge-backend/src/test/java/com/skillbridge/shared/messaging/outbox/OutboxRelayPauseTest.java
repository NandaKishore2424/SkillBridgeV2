package com.skillbridge.shared.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.shared.messaging.EventType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The two pieces of the broker-outage policy that need no broker: how long the relay
 * pauses, and the payload bound its failure classification relies on.
 *
 * <p>The policy itself -- a broker failure is not charged to the event -- is tested
 * against a real RabbitMQ in {@code OutboxRelayBrokerTest} and
 * {@code OutboxBrokerOutageTest}.
 */
class OutboxRelayPauseTest {

    @Test
    @DisplayName("the relay pauses one second, doubling to thirty")
    void pauseDoubles() {
        assertThat(OutboxRelay.pauseAfter(1, 0.0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxRelay.pauseAfter(2, 0.0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(OutboxRelay.pauseAfter(5, 0.0)).isEqualTo(Duration.ofSeconds(16));
        assertThat(OutboxRelay.pauseAfter(6, 0.0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(OutboxRelay.pauseAfter(500, 0.0)).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("jitter adds up to a quarter, and bad inputs are clamped")
    void pauseJitterAndClamps() {
        assertThat(OutboxRelay.pauseAfter(1, 1.0)).isEqualTo(Duration.ofMillis(1250));
        assertThat(OutboxRelay.pauseAfter(1, 9.0)).isEqualTo(Duration.ofMillis(1250));
        assertThat(OutboxRelay.pauseAfter(0, -1.0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(OutboxRelay.pauseAfter(6, 1.0)).isEqualTo(Duration.ofMillis(37_500));
    }

    @Test
    @DisplayName("a payload over the limit fails the write, so it can never reach the relay")
    void oversizedPayloadIsRefused() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxWriter writer = new OutboxWriter(repository, new ObjectMapper());

        assertThatThrownBy(() -> writer.write(EventType.PROFILE_UPDATED, "Student", 31L, 1L,
                Map.of("padding", "x".repeat(OutboxWriter.MAX_PAYLOAD_BYTES))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("limit");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("an ordinary event is far inside the limit")
    void ordinaryEventIsWritten() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        new OutboxWriter(repository, new ObjectMapper())
                .write(EventType.SKILL_UPDATED, "Student", 31L, 1L, new EventType.SkillUpdated(31, 7));
        verify(repository).save(any());
    }
}
