package com.skillbridge.shared.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.common.scheduling.SingleRunGuard;
import com.skillbridge.shared.messaging.EventType;
import com.skillbridge.testsupport.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.RabbitMQContainer;

import javax.sql.DataSource;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against a real RabbitMQ.
 *
 * <p>Publisher confirms, returns for unroutable messages and a broker that refuses
 * connections are broker behaviour. A mocked template answers whatever the test
 * tells it to, so none of the relay's actual guarantees would be under test.
 *
 * <p>Each relay here is built by hand around a connection factory the test
 * controls, rather than taken from the context: the application's relay is off
 * under the test profile, and a scheduled poll running alongside these
 * assertions would race them.
 *
 * <h2>What "broker down" means here</h2>
 *
 * <p>Pointing a relay at a port nothing listens on, then at the real broker. That
 * tests the property that matters — failures leave events PENDING and a later
 * run drains them — without restarting a container, which loses its mapped port
 * and turns the test flaky. It does not test a broker dying mid-publish.
 */
@SpringBootTest
@IntegrationTest
class OutboxRelayBrokerTest {

    private static final String MARKER = "OutboxRelayBrokerTest";
    /** The production queue, so a publish that arrives here was routed by the production topology. */
    private static final String QUEUE = RabbitMQConfig.AI_ANALYSIS_QUEUE;

    private static RabbitMQContainer broker;
    private static CachingConnectionFactory liveFactory;

    @Autowired private OutboxWriter writer;
    @Autowired private OutboxStore store;
    @Autowired private SingleRunGuard singleRun;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MeterRegistry meters;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void startBroker() {
        broker = new RabbitMQContainer("rabbitmq:3-management");
        broker.start();
        liveFactory = confirmingFactory(broker.getHost(), broker.getAmqpPort());

        // The production topology, not a copy of it: a routing change that strands
        // events has to fail here.
        RabbitAdmin admin = new RabbitAdmin(liveFactory);
        for (var declarable : RabbitMQConfig.topology().getDeclarables()) {
            if (declarable instanceof Exchange exchange) {
                admin.declareExchange(exchange);
            } else if (declarable instanceof Queue queue) {
                admin.declareQueue(queue);
            } else if (declarable instanceof Binding binding) {
                admin.declareBinding(binding);
            }
        }
    }

    @AfterAll
    static void stopBroker() {
        if (liveFactory != null) {
            liveFactory.destroy();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @BeforeEach
    void isolate() throws Exception {
        // The relay claims EVERY due row, not just this test's. So the table is
        // emptied first -- which is only acceptable on a throwaway database. This
        // check refuses to run against anything else rather than skipping, so it
        // cannot quietly delete a live outbox.
        try (Connection c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            // Checks what must NOT be true rather than what is: a Docker host may report
            // the container as an IP rather than localhost, and a positive check on the
            // host name would fail there for no reason.
            assertThat(url)
                    .as("this test deletes every outbox row; it must never run against Supabase")
                    .doesNotContain("supabase")
                    .doesNotContain("pooler");
        }
        jdbc.update("DELETE FROM outbox_events");
        new RabbitAdmin(liveFactory).purgeQueue(QUEUE, false);
    }

    @Test
    @DisplayName("a confirmed publish marks the event PUBLISHED and delivers the stored payload verbatim")
    void publishesAndConfirms() throws Exception {
        UUID eventId = writeEvent(RabbitMQConfig.SKILL_UPDATED_KEY);
        String stored = jdbc.queryForObject(
                "SELECT payload FROM outbox_events WHERE event_id = ?", String.class, eventId);

        int confirmed = relay(liveFactory, 8).relayOnce();

        assertThat(confirmed).isEqualTo(1);
        assertThat(status(eventId)).isEqualTo("PUBLISHED");

        Message received = new RabbitTemplate(liveFactory).receive(QUEUE, 5_000);
        assertThat(received).as("the message must actually reach the queue").isNotNull();
        String body = new String(received.getBody(), StandardCharsets.UTF_8);
        // Byte for byte. A JSON message converter would have re-serialised the stored
        // text into a quoted string, and the consumer would get a string, not an object.
        assertThat(body).isEqualTo(stored);
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        // The body is the envelope, and it names the same event the AMQP properties do.
        assertThat(objectMapper.readTree(body).get("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(received.getMessageProperties().<Integer>getHeader("schemaVersion"))
                .isEqualTo(EventType.SKILL_UPDATED.schemaVersion());
        assertThat(received.getMessageProperties().getContentType()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("an unroutable event is NOT marked published, though the broker acked it")
    void unroutableStaysPending() {
        // The broker confirms an unroutable message just like a routed one. Without
        // mandatory + returns this event would be marked PUBLISHED and silently gone.
        UUID eventId = writeEvent("no.queue.is.bound.to.this");

        int confirmed = relay(liveFactory, 8).relayOnce();

        assertThat(confirmed).isZero();
        Map<String, Object> row = row(eventId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("unroutable");
    }

    @Test
    @DisplayName("while the broker is unreachable events accumulate, and a later run drains them all")
    void accumulatesThenDrains() throws Exception {
        UUID first = writeEvent(RabbitMQConfig.SKILL_UPDATED_KEY);
        UUID second = writeEvent(RabbitMQConfig.SKILL_UPDATED_KEY);
        UUID third = writeEvent(RabbitMQConfig.SKILL_UPDATED_KEY);

        CachingConnectionFactory unreachable = confirmingFactory("localhost", closedPort());
        unreachable.setConnectionTimeout(1_000);
        try {
            int confirmed = relay(unreachable, 8).relayOnce();
            assertThat(confirmed).isZero();
        } finally {
            unreachable.destroy();
        }

        for (UUID id : new UUID[]{first, second, third}) {
            Map<String, Object> row = row(id);
            assertThat(row.get("status")).as("nothing is lost while the broker is down").isEqualTo("PENDING");
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat(row.get("last_error")).isNotNull();
        }

        // The backoff scheduled them a little into the future; bring them due now
        // rather than sleeping, so the test does not depend on timing.
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'");

        int drained = relay(liveFactory, 8).relayOnce();

        assertThat(drained).isEqualTo(3);
        for (UUID id : new UUID[]{first, second, third}) {
            assertThat(status(id)).isEqualTo("PUBLISHED");
        }
        RabbitTemplate reader = new RabbitTemplate(liveFactory);
        for (int i = 0; i < 3; i++) {
            assertThat(reader.receive(QUEUE, 5_000)).as("message %d of 3", i + 1).isNotNull();
        }
    }

    @Test
    @DisplayName("an event that keeps failing reaches DEAD at the attempt limit instead of retrying for ever")
    void failingEventGoesDead() {
        UUID eventId = writeEvent("no.queue.is.bound.to.this");

        relay(liveFactory, 1).relayOnce();

        Map<String, Object> row = row(eventId);
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat((String) row.get("last_error")).contains("unroutable");
    }

    @Test
    @DisplayName("an event whose relay kept crashing is declared DEAD when claimed past the limit")
    void crashLoopingEventGoesDead() {
        // Simulates a relay that claimed this row max-attempts times and died each
        // time before recording anything: attempts at the limit, still PENDING, due.
        UUID eventId = writeEvent(RabbitMQConfig.SKILL_UPDATED_KEY);
        jdbc.update("UPDATE outbox_events SET attempts = 3, next_attempt_at = now() - interval '1 second' "
                + "WHERE event_id = ?", eventId);

        int confirmed = relay(liveFactory, 3).relayOnce();

        assertThat(confirmed).as("a crash-looping event must not be published a fourth time").isZero();
        Map<String, Object> row = row(eventId);
        assertThat(row.get("status")).isEqualTo("DEAD");
        assertThat((String) row.get("last_error")).contains("no recorded outcome");
    }

    // ---------------------------------------------------------------- helpers

    /**
     * A SKILL_UPDATED event, re-pointed at {@code routingKey} when that is not the real one.
     *
     * <p>The writer takes the routing key from the event type, as it should; a test that
     * needs an unroutable event changes the stored row rather than the production API.
     */
    private UUID writeEvent(String routingKey) {
        UUID eventId = new TransactionTemplate(transactionManager).execute(status ->
                writer.write(EventType.SKILL_UPDATED, MARKER, 31L, 1L, new EventType.SkillUpdated(31, 7)));
        if (!routingKey.equals(EventType.SKILL_UPDATED.routingKey())) {
            jdbc.update("UPDATE outbox_events SET routing_key = ? WHERE event_id = ?", routingKey, eventId);
        }
        return eventId;
    }

    private OutboxRelay relay(CachingConnectionFactory factory, int maxAttempts) {
        return new OutboxRelay(store, singleRun, objectMapper, meters, factory,
                100, 60_000, maxAttempts, 5_000, 7);
    }

    private static CachingConnectionFactory confirmingFactory(String host, int port) {
        CachingConnectionFactory factory = new CachingConnectionFactory(host, port);
        factory.setUsername("guest");
        factory.setPassword("guest");
        // The same two settings application.yaml makes; the relay depends on both.
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        return factory;
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();   // closed on return, so nothing is listening
        }
    }

    private String status(UUID eventId) {
        return jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
    }

    private Map<String, Object> row(UUID eventId) {
        return jdbc.queryForMap(
                "SELECT status, attempts, last_error FROM outbox_events WHERE event_id = ?", eventId);
    }
}
