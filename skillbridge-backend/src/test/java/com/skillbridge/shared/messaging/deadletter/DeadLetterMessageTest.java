package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.impl.LongStringHelper;
import com.skillbridge.common.config.RabbitMQConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a DLQ message becomes a row, with no broker and no database.
 *
 * <p>Most of this is about hostile input, because that is what a DLQ holds. Each
 * case here is a message that, stored naively, fails the insert — and the recorder
 * does not drop what it cannot store, so one of these would block every dead
 * letter behind it. {@code DeadLetterServiceTest} inserts the same shapes into a
 * real PostgreSQL; this proves the shaping, that proves Postgres accepts it.
 */
class DeadLetterMessageTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant RECEIVED = Instant.parse("2026-09-16T10:00:00Z");
    private static final UUID EVENT = UUID.fromString("9d6c1c8e-3f4a-4c55-8f4e-0a1b2c3d4e5f");
    private static final String BODY = "{\"eventType\":\"SKILL_UPDATED\",\"studentId\":31,\"collegeId\":1}";

    @Nested
    @DisplayName("a message the consumer dead-lettered itself")
    class ConsumerDeadLetter {

        @Test
        @DisplayName("takes the reason, time and queue from the consumer's headers")
        void readsTheConsumersHeaders() throws Exception {
            MessageProperties props = consumerProps("no handler for event type 'NOPE'");
            props.setHeader(RabbitMQConfig.RETRY_ATTEMPT_HEADER, 3);
            props.setHeader("traceId", "trace-1");

            DeadLetterMessage m = DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED);

            assertThat(m.deathReason()).isEqualTo(DeadLetterMessage.CONSUMER);
            assertThat(m.failureReason()).isEqualTo("no handler for event type 'NOPE'");
            assertThat(m.sourceQueue()).isEqualTo(RabbitMQConfig.AI_ANALYSIS_QUEUE);
            assertThat(m.failedAt()).isEqualTo(Instant.ofEpochMilli(1_789_000_000_123L));
            assertThat(m.retryCount()).isEqualTo(3);
            assertThat(m.eventId()).isEqualTo(EVENT);
            assertThat(m.eventType()).isEqualTo("SKILL_UPDATED");
            assertThat(m.routingKey()).isEqualTo("ai.skill.updated");
            assertThat(m.payload()).isEqualTo(BODY);
            assertThat(m.payloadEncoding()).isEqualTo(DeadLetterEvent.UTF8);
            assertThat(JSON.readTree(m.payloadJson())).isEqualTo(JSON.readTree(BODY));
            assertThat(JSON.readTree(m.headersJson()).get("traceId").asText()).isEqualTo("trace-1");
        }

        @Test
        @DisplayName("reads a reason longer than 1024 bytes, in either form Spring AMQP leaves it in")
        void readsALongReason() {
            // Spring AMQP converts a header string to String only up to 1024 bytes, and the
            // consumer's reasons run to 2000 characters. Past the limit the value stays a
            // LongString, or becomes a DataInputStream if the converter is configured so;
            // String.valueOf on the stream would store "java.io.DataInputStream@1b2c3d".
            String reason = "retries exhausted after 3 attempts; last failure: " + "x".repeat(1500);
            byte[] bytes = reason.getBytes(StandardCharsets.UTF_8);

            for (Object form : new Object[]{
                    LongStringHelper.asLongString(bytes),
                    new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))}) {
                MessageProperties props = consumerProps("placeholder");
                props.setHeader(RabbitMQConfig.FAILURE_REASON_HEADER, form);

                DeadLetterMessage m = DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED);

                assertThat(m.failureReason()).as(form.getClass().getSimpleName()).isEqualTo(reason);
            }
        }

        @Test
        @DisplayName("falls back to the time it was received when x-failed-at is missing or not a time")
        void failedAtFallsBack() {
            for (Object bad : new Object[]{null, "yesterday", 42, -5L}) {
                MessageProperties props = consumerProps("bad");
                props.setHeader(RabbitMQConfig.FAILED_AT_HEADER, bad);
                assertThat(DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED).failedAt())
                        .as("x-failed-at = %s", bad).isEqualTo(RECEIVED);
            }
        }
    }

    @Nested
    @DisplayName("a message the broker dead-lettered")
    class BrokerDeadLetter {

        @Test
        @DisplayName("takes the most recent x-death entry, and explains what its reason usually means")
        void readsXDeath() {
            MessageProperties props = baseProps();
            Date died = Date.from(Instant.parse("2026-09-16T09:59:30Z"));
            props.setHeader("x-death", List.of(
                    Map.of("reason", "delivery_limit", "queue", RabbitMQConfig.AI_ANALYSIS_QUEUE,
                            "count", 1L, "time", died, "exchange", "skillbridge.events"),
                    Map.of("reason", "expired", "queue", "skillbridge.ai.analysis.retry.5s",
                            "count", 2L, "time", new Date(0))));

            DeadLetterMessage m = DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED);

            assertThat(m.deathReason()).isEqualTo("delivery_limit");
            assertThat(m.sourceQueue()).isEqualTo(RabbitMQConfig.AI_ANALYSIS_QUEUE);
            assertThat(m.failedAt()).isEqualTo(died.toInstant());
            assertThat(m.failureReason()).contains("delivery limit").contains("crashed");
        }

        @Test
        @DisplayName("a bare rejection says where to look for the reason it lost")
        void bareRejection() {
            assertThat(DeadLetterMessage.describeBrokerDeath("rejected", "q", 1))
                    .contains("without requeue").contains("logs");
        }

        @Test
        @DisplayName("an x-death header that is not a list is treated as absent, not as a crash")
        void garbageXDeath() {
            MessageProperties props = baseProps();
            props.setHeader("x-death", "not a list");

            DeadLetterMessage m = DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED);

            assertThat(m.deathReason()).isEqualTo(DeadLetterMessage.UNKNOWN);
            assertThat(m.failedAt()).isEqualTo(RECEIVED);
        }
    }

    @Nested
    @DisplayName("bodies Postgres would refuse")
    class HostileBodies {

        @Test
        @DisplayName("a body that is not UTF-8 is stored base64-encoded, and not as JSON")
        void notUtf8() {
            byte[] body = {(byte) 0xC3, (byte) 0x28, 'x'};
            DeadLetterMessage m = DeadLetterMessage.from(new Message(body, baseProps()), JSON, RECEIVED);

            assertThat(m.payloadEncoding()).isEqualTo(DeadLetterEvent.BASE64);
            assertThat(Base64.getDecoder().decode(m.payload())).isEqualTo(body);
            assertThat(m.payloadJson()).isNull();
        }

        @Test
        @DisplayName("a NUL byte, which TEXT refuses, also means base64")
        void nulByte() {
            byte[] body = "{\"a\":\"b\u0000c\"}".getBytes(StandardCharsets.UTF_8);
            DeadLetterMessage m = DeadLetterMessage.from(new Message(body, baseProps()), JSON, RECEIVED);

            assertThat(m.payloadEncoding()).isEqualTo(DeadLetterEvent.BASE64);
        }

        @Test
        @DisplayName("JSON that jsonb refuses -- an escaped NUL, an unpaired surrogate -- keeps its text but not its JSON")
        void jsonThatJsonbRefuses() {
            for (String body : List.of("{\"a\":\"\\u0000\"}", "{\"a\":\"\\ud800\"}", "{\"a\":\"\\uDC00x\"}")) {
                DeadLetterMessage m = DeadLetterMessage.from(message(body, baseProps()), JSON, RECEIVED);
                assertThat(m.payload()).as(body).isEqualTo(body);
                assertThat(m.payloadEncoding()).as(body).isEqualTo(DeadLetterEvent.UTF8);
                assertThat(m.payloadJson()).as(body).isNull();
            }
        }

        @Test
        @DisplayName("text that is not JSON is kept as text")
        void notJson() {
            DeadLetterMessage m = DeadLetterMessage.from(message("not json {", baseProps()), JSON, RECEIVED);
            assertThat(m.payload()).isEqualTo("not json {");
            assertThat(m.payloadJson()).isNull();
        }

        @Test
        @DisplayName("an empty body is stored as an empty string")
        void emptyBody() {
            DeadLetterMessage m = DeadLetterMessage.from(new Message(new byte[0], baseProps()), JSON, RECEIVED);
            assertThat(m.payload()).isEmpty();
            assertThat(m.payloadJson()).isNull();
        }
    }

    @Nested
    @DisplayName("headers Postgres would refuse")
    class HostileHeaders {

        @Test
        @DisplayName("every AMQP header type becomes JSON jsonb accepts")
        void anyHeaderType() throws Exception {
            MessageProperties props = baseProps();
            props.setHeader("nul", "a\u0000b");
            props.setHeader("bytes", new byte[]{1, 2, 3});
            props.setHeader("nan", Double.NaN);
            props.setHeader("when", new Date(0));
            props.setHeader("nested", Map.of("list", List.of("x\u0000", 1, Map.of("deep", true))));
            props.setHeader("lone", "\uD800");

            DeadLetterMessage m = DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED);
            JsonNode headers = JSON.readTree(m.headersJson());

            assertThat(m.headersJson()).doesNotContain("\\u0000").doesNotContain("\u0000");
            assertThat(headers.get("nul").asText()).isEqualTo("a\uFFFDb");
            assertThat(headers.get("bytes").get("base64").asText()).isEqualTo("AQID");
            assertThat(headers.get("nan").asText()).isEqualTo("NaN");
            assertThat(headers.get("when").asText()).isEqualTo("1970-01-01T00:00:00Z");
            assertThat(headers.at("/nested/list/0").asText()).isEqualTo("x\uFFFD");
            assertThat(headers.get("lone").asText()).isEqualTo("\uFFFD");
        }

        @Test
        @DisplayName("strings are cut to their column widths without splitting a surrogate pair")
        void clipped() {
            MessageProperties props = baseProps();
            props.setHeader("eventType", "T".repeat(99) + "\uD83D\uDE00" + "rest");
            props.setReceivedRoutingKey("k".repeat(300));
            props.setHeader(RabbitMQConfig.FAILURE_REASON_HEADER, "r".repeat(5000));

            DeadLetterMessage m = DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED);

            assertThat(m.eventType()).isEqualTo("T".repeat(99));
            assertThat(m.routingKey()).hasSize(255);
            assertThat(m.failureReason()).hasSize(RabbitMQConfig.MAX_FAILURE_REASON_LENGTH);
        }

        @Test
        @DisplayName("a message id that is not a UUID gives no event id, and the eventId header is the fallback")
        void eventIdFallback() {
            MessageProperties props = baseProps();
            props.setMessageId("not-a-uuid");
            assertThat(DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED).eventId()).isNull();

            props.setHeader("eventId", EVENT.toString());
            assertThat(DeadLetterMessage.from(message(BODY, props), JSON, RECEIVED).eventId()).isEqualTo(EVENT);
        }
    }

    @Nested
    @DisplayName("the fingerprint")
    class Fingerprint {

        @Test
        @DisplayName("is the same for a redelivery, and for the same event failing the same way later")
        void stable() {
            MessageProperties first = consumerProps("boom");
            MessageProperties later = consumerProps("boom");
            later.setHeader(RabbitMQConfig.FAILED_AT_HEADER, 1_789_999_999_999L);

            assertThat(DeadLetterMessage.from(message(BODY, first), JSON, RECEIVED).fingerprint())
                    .isEqualTo(DeadLetterMessage.from(message(BODY, first), JSON, RECEIVED.plusSeconds(60)).fingerprint())
                    .isEqualTo(DeadLetterMessage.from(message(BODY, later), JSON, RECEIVED).fingerprint())
                    .hasSize(64);
        }

        @Test
        @DisplayName("differs by message id, by failure, and by body")
        void sensitive() {
            String base = DeadLetterMessage.from(message(BODY, consumerProps("boom")), JSON, RECEIVED).fingerprint();

            MessageProperties otherId = consumerProps("boom");
            otherId.setMessageId(UUID.randomUUID().toString());

            assertThat(DeadLetterMessage.from(message(BODY, otherId), JSON, RECEIVED).fingerprint()).isNotEqualTo(base);
            assertThat(DeadLetterMessage.from(message(BODY, consumerProps("bang")), JSON, RECEIVED).fingerprint())
                    .isNotEqualTo(base);
            assertThat(DeadLetterMessage.from(message(BODY + " ", consumerProps("boom")), JSON, RECEIVED).fingerprint())
                    .isNotEqualTo(base);
        }
    }

    @Test
    @DisplayName("with no reason and no x-death, the reason says so and the time is when it was received")
    void noDeathInformation() {
        DeadLetterMessage m = DeadLetterMessage.from(message(BODY, baseProps()), JSON, RECEIVED);
        assertThat(m.deathReason()).isEqualTo(DeadLetterMessage.UNKNOWN);
        assertThat(m.failureReason()).contains("without saying why");
        assertThat(m.failedAt()).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("unreadable() keeps the bytes whatever went wrong")
    void unreadable() {
        byte[] body = {0, 1, 2};
        DeadLetterMessage m = DeadLetterMessage.unreadable(
                new Message(body, baseProps()), new IllegalStateException("parser bug"), RECEIVED);
        assertThat(Base64.getDecoder().decode(m.payload())).isEqualTo(body);
        assertThat(m.payloadEncoding()).isEqualTo(DeadLetterEvent.BASE64);
        assertThat(m.failureReason()).contains("parser bug");
        assertThat(m.fingerprint()).hasSize(64);
    }

    // ---------------------------------------------------------------- helpers

    private static MessageProperties baseProps() {
        MessageProperties props = new MessageProperties();
        props.setMessageId(EVENT.toString());
        props.setReceivedRoutingKey("ai.skill.updated");
        props.setHeader("eventType", "SKILL_UPDATED");
        return props;
    }

    private static MessageProperties consumerProps(String reason) {
        MessageProperties props = baseProps();
        props.setHeader(RabbitMQConfig.FAILURE_REASON_HEADER, reason);
        props.setHeader(RabbitMQConfig.FAILED_AT_HEADER, 1_789_000_000_123L);
        props.setHeader(RabbitMQConfig.FAILED_QUEUE_HEADER, RabbitMQConfig.AI_ANALYSIS_QUEUE);
        return props;
    }

    private static Message message(String body, MessageProperties props) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }
}
