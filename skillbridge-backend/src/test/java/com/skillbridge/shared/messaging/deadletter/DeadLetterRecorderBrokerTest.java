package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.common.scheduling.SingleRunGuard;
import com.skillbridge.shared.messaging.MessagingMetrics;
import com.skillbridge.shared.messaging.outbox.OutboxRelay;
import com.skillbridge.shared.messaging.outbox.OutboxStore;
import com.skillbridge.testsupport.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The recorder running for real: a RabbitMQ, a PostgreSQL, and the application's own
 * listener container between them.
 *
 * <p>What only this can show: what the broker and the client library actually put
 * in a dead letter's headers (the unit tests build those by hand), that the ack
 * waits for the insert, that a database failure holds the message rather than
 * dropping or spinning it, and that a replay goes all the way round -- outbox,
 * relay, exchange, back into the analysis queue.
 *
 * <p>The broker is started once and left for Testcontainers to remove at exit. The
 * context is dirtied after the class, so this listener is closed before anything
 * else runs, and never finds its broker gone.
 */
@SpringBootTest(properties = "dead-letters.recorder.enabled=true")
@IntegrationTest
@DirtiesContext
class DeadLetterRecorderBrokerTest {

    private static final RabbitMQContainer BROKER = new RabbitMQContainer("rabbitmq:3-management");
    private static final String SKILL = "{\"eventType\":\"SKILL_UPDATED\",\"studentId\":31,\"collegeId\":1}";
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        BROKER.start();
    }

    @DynamicPropertySource
    static void broker(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.addresses",
                () -> "amqp://guest:guest@" + BROKER.getHost() + ":" + BROKER.getAmqpPort());
    }

    @Autowired private ConnectionFactory connectionFactory;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private DeadLetterService deadLetters;
    @Autowired private MessagingMetrics metrics;
    @Autowired private MeterRegistry meters;
    @Autowired private OutboxStore outboxStore;
    @Autowired private SingleRunGuard singleRun;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;

    private RabbitTemplate template;
    private RabbitAdmin admin;

    @BeforeEach
    void setUp() throws Exception {
        try (java.sql.Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("this test empties dead_letter_events and outbox_events; never against Supabase")
                    .doesNotContain("supabase").doesNotContain("pooler");
        }
        template = new RabbitTemplate(connectionFactory);
        admin = new RabbitAdmin(connectionFactory);
        assertThat(listeners.getListenerContainer(DeadLetterRecorder.LISTENER_ID).isRunning())
                .as("the recorder is listening").isTrue();

        admin.purgeQueue(RabbitMQConfig.AI_ANALYSIS_QUEUE, false);
        RabbitMQConfig.RETRY_TIERS.forEach(t -> admin.purgeQueue(t.queue(), false));
        jdbc.update("DELETE FROM dead_letter_events");
        jdbc.update("DELETE FROM outbox_events");
    }

    @Test
    @DisplayName("a message the analysis queue rejects is recorded with the broker's reason, and leaves the DLQ")
    void brokerRejection() throws Exception {
        UUID eventId = UUID.randomUUID();
        publish(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.SKILL_UPDATED_KEY, SKILL, eventId, Map.of());

        try (Connection connection = connectionFactory.createConnection();
             Channel channel = connection.createChannel(false)) {
            GetResponse delivery = get(channel, RabbitMQConfig.AI_ANALYSIS_QUEUE);
            assertThat(delivery).isNotNull();
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
        }

        Map<String, Object> row = awaitRow(eventId);
        assertThat(row.get("death_reason")).isEqualTo("rejected");
        assertThat(row.get("source_queue")).isEqualTo(RabbitMQConfig.AI_ANALYSIS_QUEUE);
        assertThat((String) row.get("failure_reason")).contains("without requeue");
        assertThat(row.get("payload")).isEqualTo(SKILL);
        assertThat(row.get("routing_key")).isEqualTo(RabbitMQConfig.SKILL_UPDATED_KEY);
        assertThat(row.get("event_type")).isEqualTo("SKILL_UPDATED");
        assertThat(row.get("headers")).asString().contains("x-death");
        assertDeadLetterQueueEmpty();
    }

    @Test
    @DisplayName("the consumer's own dead letter is recorded with its full reason, time, queue and retry count")
    void consumerDeadLetter() throws Exception {
        // Longer than 1024 bytes, so it reaches the recorder as the client's long-string
        // type rather than a String -- through the real client, not a hand-built header.
        String reason = "retries exhausted after 3 attempts; last failure: OperationalError: "
                + "connection refused ".repeat(80);
        assertThat(reason.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(1024);
        UUID eventId = UUID.randomUUID();

        publish(RabbitMQConfig.DEAD_LETTER_EXCHANGE, RabbitMQConfig.AI_ANALYSIS_QUEUE, SKILL, eventId, Map.of(
                RabbitMQConfig.FAILURE_REASON_HEADER, reason,
                RabbitMQConfig.FAILED_AT_HEADER, 1_789_000_000_123L,
                RabbitMQConfig.FAILED_QUEUE_HEADER, RabbitMQConfig.AI_ANALYSIS_QUEUE,
                RabbitMQConfig.RETRY_ATTEMPT_HEADER, 3));

        Map<String, Object> row = awaitRow(eventId);
        assertThat(row.get("death_reason")).isEqualTo("consumer");
        assertThat(row.get("failure_reason")).isEqualTo(reason.substring(0, Math.min(reason.length(),
                RabbitMQConfig.MAX_FAILURE_REASON_LENGTH)));
        assertThat(((java.sql.Timestamp) row.get("failed_at")).toInstant())
                .isEqualTo(Instant.ofEpochMilli(1_789_000_000_123L));
        assertThat(row.get("retry_count")).isEqualTo(3);
        assertThat(row.get("source_queue")).isEqualTo(RabbitMQConfig.AI_ANALYSIS_QUEUE);
        assertDeadLetterQueueEmpty();
    }

    @Test
    @DisplayName("the same dead letter delivered twice is one row, and both deliveries are acked")
    void duplicateIsOneRow() throws Exception {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> headers = Map.of(RabbitMQConfig.FAILURE_REASON_HEADER, "no handler",
                RabbitMQConfig.FAILED_AT_HEADER, System.currentTimeMillis());

        publish(RabbitMQConfig.DEAD_LETTER_EXCHANGE, "", SKILL, eventId, headers);
        awaitRow(eventId);
        double duplicatesBefore = recorded("duplicate");
        publish(RabbitMQConfig.DEAD_LETTER_EXCHANGE, "", SKILL, eventId, headers);

        eventually(Duration.ofSeconds(10), () -> recorded("duplicate") >= duplicatesBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
        assertDeadLetterQueueEmpty();
    }

    @Test
    @DisplayName("a body that is not text is stored, base64-encoded, and does not block the queue")
    void binaryBody() throws Exception {
        UUID eventId = UUID.randomUUID();
        byte[] body = {0, (byte) 0xFF, 0x10, 0};
        MessageProperties props = new MessageProperties();
        props.setMessageId(eventId.toString());
        props.setHeader(RabbitMQConfig.FAILURE_REASON_HEADER, "unparseable message body");
        send(RabbitMQConfig.DEAD_LETTER_EXCHANGE, "", new Message(body, props));

        Map<String, Object> row = awaitRow(eventId);
        assertThat(row.get("payload_encoding")).isEqualTo("base64");
        assertThat(Base64.getDecoder().decode((String) row.get("payload"))).isEqualTo(body);
        assertDeadLetterQueueEmpty();
    }

    @Test
    @DisplayName("while the table refuses inserts the message is held, unacked and not requeued; then it is recorded")
    void holdsTheMessageWhileTheDatabaseFails() throws Exception {
        // A trigger that fails every insert stands in for a database that is down,
        // without taking down the database every other part of the context uses.
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION dead_letter_test_refuse() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'dead_letter_events is refusing inserts (test)'; END $$ LANGUAGE plpgsql""");
        jdbc.execute("CREATE TRIGGER dead_letter_test_refuse BEFORE INSERT ON dead_letter_events "
                + "FOR EACH ROW EXECUTE FUNCTION dead_letter_test_refuse()");
        UUID eventId = UUID.randomUUID();
        double failuresBefore = failures();
        try {
            long published = System.nanoTime();
            publish(RabbitMQConfig.DEAD_LETTER_EXCHANGE, "", SKILL, eventId,
                    Map.of(RabbitMQConfig.FAILURE_REASON_HEADER, "boom"));

            eventually(Duration.ofSeconds(15), () -> failures() >= failuresBefore + 2);
            // The management statistics are refreshed every five seconds, so wait for
            // them to show the held message, and for at least one refresh after it.
            eventually(Duration.ofSeconds(15), () -> {
                try {
                    return managementApi("/api/queues/%2F/" + RabbitMQConfig.DEAD_LETTER_QUEUE)
                            .path("messages_unacknowledged").asInt() == 1;
                } catch (Exception e) {
                    return false;
                }
            });
            long waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - published);
            if (waited < 6_500) {
                Thread.sleep(6_500 - waited);
            }
            JsonNode queue = managementApi("/api/queues/%2F/" + RabbitMQConfig.DEAD_LETTER_QUEUE);
            assertThat(queue.path("messages_ready").asInt()).as("not put back in the queue").isZero();
            assertThat(queue.path("message_stats").path("redeliver").asInt(0))
                    .as("never redelivered while held: a nack-and-requeue would redeliver at once").isZero();
            assertThat(failures() - failuresBefore)
                    .as("attempts back off (1s, 2s, 4s...), they do not spin").isBetween(2.0, 5.0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events WHERE event_id = ?",
                    Integer.class, eventId)).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS dead_letter_test_refuse ON dead_letter_events");
            jdbc.execute("DROP FUNCTION IF EXISTS dead_letter_test_refuse()");
        }

        Map<String, Object> row = awaitRow(eventId);
        assertThat(row.get("failure_reason")).isEqualTo("boom");
        assertDeadLetterQueueEmpty();
    }

    @Test
    @DisplayName("a replay goes all the way round: outbox, relay, exchange, analysis queue, with a new event id")
    void replayReachesTheAnalysisQueue() throws Exception {
        UUID original = UUID.randomUUID();
        publish(RabbitMQConfig.DEAD_LETTER_EXCHANGE, RabbitMQConfig.AI_ANALYSIS_QUEUE, SKILL, original,
                Map.of(RabbitMQConfig.FAILURE_REASON_HEADER, "model failed"));
        long id = ((Number) awaitRow(original).get("id")).longValue();

        UUID replayId = deadLetters.replay(id, 900L);
        int confirmed = new OutboxRelay(outboxStore, singleRun, objectMapper, meters, connectionFactory,
                100, 60_000, 8, 5_000, 7).relayOnce();

        assertThat(confirmed).isEqualTo(1);
        Message delivered = template.receive(RabbitMQConfig.AI_ANALYSIS_QUEUE, 10_000);
        assertThat(delivered).as("the replay reached the analysis queue").isNotNull();
        assertThat(delivered.getMessageProperties().getMessageId()).isEqualTo(replayId.toString())
                .isNotEqualTo(original.toString());
        assertThat(delivered.getMessageProperties().getReceivedRoutingKey()).isEqualTo(RabbitMQConfig.SKILL_UPDATED_KEY);
        assertThat(JSON.readTree(delivered.getBody())).isEqualTo(JSON.readTree(SKILL));
        assertThat(jdbc.queryForObject("SELECT status FROM dead_letter_events WHERE id = ?", String.class, id))
                .isEqualTo("REPLAYED");
    }

    @Test
    @DisplayName("the queue-depth gauge reads the broker's real count")
    void metricsReadTheBroker() throws Exception {
        for (int i = 0; i < 3; i++) {
            publish(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.SKILL_UPDATED_KEY, SKILL, UUID.randomUUID(), Map.of());
        }

        eventually(Duration.ofSeconds(10), () -> {
            metrics.refresh();
            return gauge(RabbitMQConfig.AI_ANALYSIS_QUEUE) == 3.0;
        });
        assertThat(gauge(RabbitMQConfig.DEAD_LETTER_QUEUE)).isZero();
        assertThat(meters.get(MessagingMetrics.OUTBOX_EVENTS).tag("status", "pending").gauge().value()).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private void publish(String exchange, String key, String body, UUID messageId, Map<String, Object> headers)
            throws Exception {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setMessageId(messageId.toString());
        props.setHeader("eventType", "SKILL_UPDATED");
        headers.forEach(props::setHeader);
        send(exchange, key, new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }

    private void send(String exchange, String key, Message message) throws Exception {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        template.send(exchange, key, message, correlation);
        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck()).isTrue();
        assertThat(correlation.getReturned()).as("routed to a queue").isNull();
    }

    private Map<String, Object> awaitRow(UUID eventId) throws Exception {
        eventually(Duration.ofSeconds(20), () -> jdbc.queryForObject(
                "SELECT count(*) FROM dead_letter_events WHERE event_id = ?", Integer.class, eventId) > 0);
        return jdbc.queryForMap("SELECT * FROM dead_letter_events WHERE event_id = ?", eventId);
    }

    private double recorded(String outcome) {
        return meters.find(MessagingMetrics.DEAD_LETTER_RECORDED).tag("outcome", outcome)
                .counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private void assertDeadLetterQueueEmpty() throws Exception {
        eventually(Duration.ofSeconds(10), () -> {
            try {
                JsonNode queue = managementApi("/api/queues/%2F/" + RabbitMQConfig.DEAD_LETTER_QUEUE);
                return queue.get("messages").asInt() == 0;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private double failures() {
        return meters.get(MessagingMetrics.DEAD_LETTER_RECORD_FAILURES).counter().count();
    }

    private double gauge(String queue) {
        return meters.get(MessagingMetrics.QUEUE_MESSAGES).tag("queue", queue).gauge().value();
    }

    private static GetResponse get(Channel channel, String queue) throws Exception {
        for (int i = 0; i < 50; i++) {
            GetResponse r = channel.basicGet(queue, false);
            if (r != null) {
                return r;
            }
            Thread.sleep(100);
        }
        return null;
    }

    /** Polls until the condition holds; the management API's statistics lag by a few seconds. */
    private static void eventually(Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(200);
        }
    }

    private static JsonNode managementApi(String path) throws Exception {
        String auth = Base64.getEncoder().encodeToString(
                (BROKER.getAdminUsername() + ":" + BROKER.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(BROKER.getHttpUrl() + path))
                .header("Authorization", "Basic " + auth).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return JSON.readTree(response.body());
    }
}
