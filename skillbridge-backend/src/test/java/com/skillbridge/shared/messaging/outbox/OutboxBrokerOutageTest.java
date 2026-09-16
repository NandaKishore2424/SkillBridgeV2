package com.skillbridge.shared.messaging.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.common.scheduling.SingleRunGuard;
import com.skillbridge.shared.messaging.EventType;
import com.skillbridge.testsupport.IntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.RabbitMQContainer;

import javax.sql.DataSource;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The broker goes away, the application keeps writing, and no event is lost.
 *
 * <p>A real RabbitMQ is killed — not pointed away from, as {@code OutboxRelayBrokerTest}
 * does — while the relay polls on its production schedule and events keep being
 * written. Then it is started again, and every event must arrive.
 *
 * <p>The outage lasts 90 seconds by default, deliberately longer than the roughly one
 * minute in which the first version of the relay charged an event its eight attempts
 * and declared it DEAD. {@code -Doutbox.outage.seconds=300} runs the five minutes the
 * phase's exit checklist names.
 *
 * <h2>A fixed port</h2>
 *
 * <p>Testcontainers maps a random host port, and Docker assigns a new random one when a
 * stopped container is started again — so a restarted broker would come back where
 * nothing is looking for it. This container is bound to a port chosen once, which is
 * what a real broker's address is.
 */
@SpringBootTest
@IntegrationTest
@Slf4j
class OutboxBrokerOutageTest {

    private static final int AMQP_PORT = freePort();
    private static final String QUEUE = RabbitMQConfig.AI_ANALYSIS_QUEUE;
    private static final String MARKER = "OutboxBrokerOutageTest";

    private static RabbitMQContainer broker;
    private static DockerClient docker;

    @Autowired private OutboxWriter writer;
    @Autowired private OutboxStore store;
    @Autowired private SingleRunGuard singleRun;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    private CachingConnectionFactory factory;
    private MeterRegistry meters;

    @BeforeAll
    static void startBroker() throws Exception {
        broker = new RabbitMQContainer("rabbitmq:3-management")
                // Only AMQP: Testcontainers waits for every exposed port to be mapped,
                // and only this one is bound, at a fixed port.
                .withExposedPorts(5672)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                        new PortBinding(Ports.Binding.bindPort(AMQP_PORT), new ExposedPort(5672))));
        broker.start();
        docker = DockerClientFactory.instance().client();
        awaitBroker(Duration.ofSeconds(60), false);

        CachingConnectionFactory setup = confirmingFactory();
        try {
            RabbitAdmin admin = new RabbitAdmin(setup);
            for (var declarable : RabbitMQConfig.topology().getDeclarables()) {
                if (declarable instanceof Exchange exchange) {
                    admin.declareExchange(exchange);
                } else if (declarable instanceof Queue queue) {
                    admin.declareQueue(queue);
                } else if (declarable instanceof Binding binding) {
                    admin.declareBinding(binding);
                }
            }
        } finally {
            setup.destroy();
        }
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.stop();
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (java.sql.Connection c = dataSource.getConnection()) {
            assertThat(c.getMetaData().getURL())
                    .as("this test deletes every outbox row; it must never run against Supabase")
                    .doesNotContain("supabase").doesNotContain("pooler");
        }
        jdbc.update("DELETE FROM outbox_events");
        drainQueue();
        factory = confirmingFactory();
        meters = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("the broker is killed for longer than a minute; every event written before, during and after arrives")
    void noEventIsLostWhileTheBrokerIsDown() throws Exception {
        Duration outage = Duration.ofSeconds(Long.getLong("outbox.outage.seconds", 90));
        OutboxRelay relay = relay();
        List<UUID> written = new CopyOnWriteArrayList<>();
        AtomicBoolean running = new AtomicBoolean(true);
        ExecutorService scheduler = Executors.newSingleThreadExecutor();
        try {
            // The production schedule: a poll, then a short fixed delay, for ever.
            Future<?> polling = scheduler.submit(() -> {
                while (running.get()) {
                    relay.poll();
                    sleep(200);
                }
            });

            for (int i = 0; i < 5; i++) {
                written.add(write());
            }
            eventually(Duration.ofSeconds(30), () -> count("PUBLISHED") == 5);

            docker.killContainerCmd(broker.getContainerId()).exec();
            Instant killed = Instant.now();
            int maxAttemptsSeen = 0;
            while (Duration.between(killed, Instant.now()).compareTo(outage) < 0) {
                // The business path does not notice: every write commits.
                written.add(write());
                maxAttemptsSeen = Math.max(maxAttemptsSeen, maxPendingAttempts());
                assertThat(count("DEAD")).as("nothing dies while the broker is away").isZero();
                sleep(500);
            }
            double failuresDuringOutage = brokerFailures();

            docker.startContainerCmd(broker.getContainerId()).exec();
            Instant restarted = Instant.now();
            awaitBroker(Duration.ofSeconds(90), true);
            Instant ready = Instant.now();
            eventually(Duration.ofMinutes(3), () -> count("PENDING") == 0);
            Instant drained = Instant.now();

            running.set(false);
            polling.get(30, TimeUnit.SECONDS);

            assertThat(count("DEAD")).isZero();
            assertThat(count("PUBLISHED")).isEqualTo(written.size());
            Set<String> received = drainQueue();
            assertThat(received).as("every event reached the queue, the ones sent before the crash included")
                    .containsAll(written.stream().map(UUID::toString).toList());

            assertThat(maxAttemptsSeen).as("an unavailable broker is not charged to the events").isLessThanOrEqualTo(1);
            // One failure per pause: 1s, 2s, 4s ... 30s. A relay that retried on every
            // poll would have failed hundreds of times.
            long ceiling = 8 + outage.toSeconds() / 20;
            assertThat(failuresDuringOutage).as("the relay paused instead of spinning").isBetween(1.0, (double) ceiling);

            log.info("Outage of {}s: {} events written, all delivered; {} broker failures while down; "
                            + "broker ready {}s after start; outbox drained {}s after that",
                    outage.toSeconds(), written.size(), (long) failuresDuringOutage,
                    Duration.between(restarted, ready).toSeconds(), Duration.between(ready, drained).toSeconds());
        } finally {
            running.set(false);
            scheduler.shutdownNow();
            factory.destroy();
        }
    }

    @Test
    @DisplayName("a broker that hangs costs one timeout per batch, not one per event")
    void aHangingBrokerStallsOneAttemptNotABatch() throws Exception {
        OutboxRelay relay = relay();
        // Open the connection while the broker answers, as it would be in production.
        write();
        assertThat(relay.relayOnce()).isEqualTo(1);

        List<UUID> stuck = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 20; i++) {
            stuck.add(write());
        }
        docker.pauseContainerCmd(broker.getContainerId()).exec();
        long elapsedMs;
        try {
            long start = System.nanoTime();
            assertThat(relay.relayOnce()).isZero();
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } finally {
            docker.unpauseContainerCmd(broker.getContainerId()).exec();
        }

        // Twenty events, five seconds of confirm timeout each, would be 100 seconds.
        assertThat(elapsedMs).as("one timeout, then the batch is given back").isLessThan(15_000);
        assertThat(maxPendingAttempts()).as("and given back uncharged").isZero();
        assertThat(count("PENDING")).isEqualTo(20);

        awaitBroker(Duration.ofSeconds(30), true);
        jdbc.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'");
        eventually(Duration.ofSeconds(60), () -> {
            relay.relayOnce();
            return count("PENDING") == 0;
        });
        Set<String> received = drainQueue();
        // At least once: the event whose confirm timed out was sent, and is sent again.
        assertThat(received).containsAll(stuck.stream().map(UUID::toString).toList());
        factory.destroy();
    }

    // ---------------------------------------------------------------- helpers

    private UUID write() {
        return new TransactionTemplate(transactionManager).execute(status ->
                writer.write(EventType.SKILL_UPDATED, MARKER, 31L, 1L, new EventType.SkillUpdated(31, 7)));
    }

    private OutboxRelay relay() {
        // The production settings: batch 100, 60s lease, 8 attempts, 5s confirm timeout.
        return new OutboxRelay(store, singleRun, objectMapper, meters, factory, 100, 60_000, 8, 5_000, 7);
    }

    private double brokerFailures() {
        var counter = meters.find("outbox.broker.unavailable").counter();
        return counter != null ? counter.count() : 0.0;
    }

    private long count(String status) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE status = ?", Long.class, status);
        return n != null ? n : 0;
    }

    private int maxPendingAttempts() {
        Integer max = jdbc.queryForObject(
                "SELECT coalesce(max(attempts), 0) FROM outbox_events WHERE status = 'PENDING'", Integer.class);
        return max != null ? max : 0;
    }

    /** Takes everything in the analysis queue, returning the message ids. */
    private static Set<String> drainQueue() throws Exception {
        Set<String> ids = new HashSet<>();
        try (Connection connection = rawFactory().newConnection(); Channel channel = connection.createChannel()) {
            int empty = 0;
            while (empty < 5) {
                GetResponse message = channel.basicGet(QUEUE, true);
                if (message == null) {
                    empty++;
                    Thread.sleep(100);
                } else {
                    empty = 0;
                    ids.add(message.getProps().getMessageId());
                }
            }
        }
        return ids;
    }

    /**
     * Until the broker takes connections -- and, after a restart, until the analysis
     * queue is back. Before the first declaration there is no queue to wait for.
     */
    private static void awaitBroker(Duration timeout, boolean queueMustExist) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        Exception last = null;
        while (System.nanoTime() < deadline) {
            try (Connection connection = rawFactory().newConnection()) {
                if (queueMustExist) {
                    Channel channel = connection.createChannel();
                    channel.queueDeclarePassive(QUEUE);
                }
                return;
            } catch (Exception e) {
                last = e;
                Thread.sleep(500);
            }
        }
        throw new AssertionError("broker not ready within " + timeout, last);
    }

    private static ConnectionFactory rawFactory() {
        ConnectionFactory raw = new ConnectionFactory();
        raw.setHost("localhost");
        raw.setPort(AMQP_PORT);
        raw.setConnectionTimeout(2_000);
        return raw;
    }

    private static CachingConnectionFactory confirmingFactory() {
        CachingConnectionFactory confirming = new CachingConnectionFactory("localhost", AMQP_PORT);
        confirming.setUsername("guest");
        confirming.setPassword("guest");
        confirming.setConnectionTimeout(5_000);   // application.yaml's value
        confirming.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        confirming.setPublisherReturns(true);
        return confirming;
    }

    private static void eventually(Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + timeout);
            }
            Thread.sleep(250);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
