package com.skillbridge.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.RabbitMQContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The topology against a real RabbitMQ: that the broker does what the
 * configuration says, not merely that it accepted the declaration.
 *
 * <p>That distinction is why this class exists. A quorum queue accepts
 * {@code x-overflow: reject-publish-dlx} and then behaves differently from what it
 * names; a declaration returning 201 proved nothing. So each property here is
 * observed: the queue type the broker reports, a message actually routed, a
 * message actually coming back from a retry tier, a message actually landing in
 * the DLQ.
 *
 * <p>Not tested here, and not claimed: overflow behaviour at the 100,000-message
 * cap (filling it is too slow for this suite), and the 30s and 5m tiers end to end
 * (their TTLs are asserted as arguments; only the 5s tier is waited for).
 */
@SpringBootTest
@IntegrationTest
class MessagingTopologyBrokerTest {

    private static RabbitMQContainer broker;
    private static CachingConnectionFactory factory;
    private static RabbitTemplate template;
    private static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void startBrokerAndDeclare() {
        broker = new RabbitMQContainer("rabbitmq:3-management");
        broker.start();
        factory = new CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
        factory.setUsername(broker.getAdminUsername());
        factory.setPassword(broker.getAdminPassword());
        factory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        factory.setPublisherReturns(true);
        template = new RabbitTemplate(factory);
        template.setMandatory(true);

        RabbitAdmin admin = new RabbitAdmin(factory);
        for (Declarable d : RabbitMQConfig.topology().getDeclarables()) {
            if (d instanceof Exchange e) {
                admin.declareExchange(e);
            } else if (d instanceof Queue q) {
                admin.declareQueue(q);
            } else if (d instanceof Binding b) {
                admin.declareBinding(b);
            }
        }
    }

    @AfterAll
    static void stop() {
        if (factory != null) {
            factory.destroy();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @BeforeEach
    void purge() {
        RabbitAdmin admin = new RabbitAdmin(factory);
        admin.purgeQueue(RabbitMQConfig.AI_ANALYSIS_QUEUE, false);
        admin.purgeQueue(RabbitMQConfig.DEAD_LETTER_QUEUE, false);
        RabbitMQConfig.RETRY_TIERS.forEach(t -> admin.purgeQueue(t.queue(), false));
    }

    @Test
    @DisplayName("the broker reports every queue as quorum, with the arguments declared")
    void brokerReportsQuorumAndArguments() throws Exception {
        for (Declarable d : RabbitMQConfig.topology().getDeclarables()) {
            if (!(d instanceof Queue declared)) {
                continue;
            }
            JsonNode reported = managementApi("/api/queues/%2F/" + declared.getName());
            assertThat(reported.get("type").asText()).as("%s type", declared.getName()).isEqualTo("quorum");
            @SuppressWarnings("unchecked")
            Map<String, Object> reportedArgs = json.convertValue(reported.get("arguments"), Map.class);
            assertThat(reportedArgs).as("%s arguments", declared.getName()).isEqualTo(declared.getArguments());
        }
    }

    @Test
    @DisplayName("an ai.* event reaches the analysis queue; any other key is returned, not silently dropped")
    void routing() throws Exception {
        publish(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.SKILL_UPDATED_KEY, "routed", null);
        Message routed = template.receive(RabbitMQConfig.AI_ANALYSIS_QUEUE, 5_000);
        assertThat(routed).isNotNull();
        assertThat(body(routed)).isEqualTo("routed");

        CorrelationData returned = publish(RabbitMQConfig.EVENTS_EXCHANGE, "notification.enrollment.approved", "stray", null);
        assertThat(returned.getReturned()).as("no queue binds notification.#, so the broker returns it").isNotNull();
        assertThat(template.receive(RabbitMQConfig.AI_ANALYSIS_QUEUE, 1_000)).isNull();
    }

    @Test
    @DisplayName("the 5s retry tier holds a message for its TTL and then returns it to the analysis queue")
    void retryTierReturnsTheMessage() throws Exception {
        String tier = RabbitMQConfig.RETRY_TIERS.get(0).queue();
        long start = System.nanoTime();

        publish(RabbitMQConfig.RETRY_EXCHANGE, tier, "retry-me", Map.of("x-retry-attempt", 1));

        assertThat(template.receive(RabbitMQConfig.AI_ANALYSIS_QUEUE, 1_000))
                .as("must not be redelivered before the TTL").isNull();

        Message back = null;
        for (int i = 0; i < 15 && back == null; i++) {
            back = template.receive(RabbitMQConfig.AI_ANALYSIS_QUEUE, 1_000);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(back).as("the tier must dead-letter it back into the analysis queue").isNotNull();
        assertThat(body(back)).isEqualTo("retry-me");
        assertThat(elapsedMs).as("held for roughly its TTL").isGreaterThanOrEqualTo(4_500);
        assertThat(back.getMessageProperties().<Integer>getHeader("x-retry-attempt"))
                .as("the attempt count travels with the message").isEqualTo(1);
    }

    @Test
    @DisplayName("a message rejected from the analysis queue lands in the DLQ, marked rejected")
    void rejectedGoesToDlq() throws Exception {
        publish(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.SKILL_UPDATED_KEY, "poison", null);

        try (Connection connection = factory.createConnection(); Channel channel = connection.createChannel(false)) {
            GetResponse delivery = get(channel, RabbitMQConfig.AI_ANALYSIS_QUEUE);
            assertThat(delivery).isNotNull();
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
        }

        Message dead = template.receive(RabbitMQConfig.DEAD_LETTER_QUEUE, 10_000);
        assertThat(dead).as("a nack without requeue must not vanish").isNotNull();
        assertThat(body(dead)).isEqualTo("poison");
        List<Map<String, ?>> xDeath = dead.getMessageProperties().getXDeathHeader();
        assertThat(xDeath).isNotEmpty();
        assertThat(String.valueOf(xDeath.get(0).get("reason"))).isEqualTo("rejected");
    }

    @Test
    @DisplayName("a message redelivered past the delivery limit is dead-lettered by the broker itself")
    void deliveryLimitDeadLetters() throws Exception {
        // The consumer crashing mid-message never acks, nacks or retries it. Without a
        // delivery limit it would be redelivered, and crash the consumer, for ever.
        publish(RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.SKILL_UPDATED_KEY, "crashes-consumer", null);

        int deliveries = 0;
        try (Connection connection = factory.createConnection(); Channel channel = connection.createChannel(false)) {
            GetResponse delivery;
            while ((delivery = get(channel, RabbitMQConfig.AI_ANALYSIS_QUEUE)) != null && deliveries < 30) {
                deliveries++;
                channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, true);
            }
        }

        assertThat(deliveries).as("delivered the limit's worth of times, then no more")
                .isBetween(RabbitMQConfig.AI_QUEUE_DELIVERY_LIMIT, RabbitMQConfig.AI_QUEUE_DELIVERY_LIMIT + 1);
        Message dead = template.receive(RabbitMQConfig.DEAD_LETTER_QUEUE, 10_000);
        assertThat(dead).isNotNull();
        assertThat(body(dead)).isEqualTo("crashes-consumer");
        assertThat(String.valueOf(dead.getMessageProperties().getXDeathHeader().get(0).get("reason")))
                .isEqualTo("delivery_limit");
    }

    // ---------------------------------------------------------------- helpers

    /** Publishes with a correlated confirm and waits for it. */
    private static CorrelationData publish(String exchange, String key, String body, Map<String, Object> headers)
            throws Exception {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        if (headers != null) {
            headers.forEach(props::setHeader);
        }
        CorrelationData correlation = new CorrelationData(key + "-" + System.nanoTime());
        template.send(exchange, key, new Message(body.getBytes(StandardCharsets.UTF_8), props), correlation);
        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck()).as("broker acked %s", key).isTrue();
        return correlation;
    }

    /** basicGet with a short poll: quorum queues can take a moment to make a requeued message available. */
    private static GetResponse get(Channel channel, String queue) throws Exception {
        for (int i = 0; i < 20; i++) {
            GetResponse r = channel.basicGet(queue, false);
            if (r != null) {
                return r;
            }
            Thread.sleep(100);
        }
        return null;
    }

    private static String body(Message m) {
        return new String(m.getBody(), StandardCharsets.UTF_8);
    }

    private static JsonNode managementApi(String path) throws Exception {
        String auth = Base64.getEncoder().encodeToString(
                (broker.getAdminUsername() + ":" + broker.getAdminPassword()).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(broker.getHttpUrl() + path))
                .header("Authorization", "Basic " + auth).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET %s", path).isEqualTo(200);
        return json.readTree(response.body());
    }
}
