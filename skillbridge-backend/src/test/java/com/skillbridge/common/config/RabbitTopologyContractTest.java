package com.skillbridge.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Java half of the broker topology contract.
 *
 * <p>{@code contracts/amqp/topology.json} names every exchange, queue, argument and
 * routing key. {@link RabbitMQConfig} declares them and the Python consumer
 * connects to them, and each side is tested against that one file, so neither can
 * rename a queue or change an argument alone. The consequence of drift is not
 * subtle — a consumer bound to a name nobody publishes to, or a
 * {@code PRECONDITION_FAILED} that closes the channel — but it only shows up when
 * both services run against one broker.
 *
 * <p>Two assertions exist only because the phase document got them wrong, and
 * both would look like reasonable configuration in review:
 * {@code reject-publish-dlx} on a quorum queue, and a static retry routing key on
 * the main queue.
 *
 * <p>No broker and no Spring context; {@code MessagingTopologyBrokerTest} checks
 * the broker honours all of this.
 */
class RabbitTopologyContractTest {

    private static JsonNode contract;
    private static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void loadContract() throws Exception {
        Path file = repositoryRoot().resolve("contracts/amqp/topology.json");
        assertThat(file).as("the topology contract is a committed artifact").isReadable();
        contract = json.readTree(Files.readString(file));
    }

    @Test
    @DisplayName("exchange and queue names match the contract")
    void namesMatch() {
        assertThat(RabbitMQConfig.EVENTS_EXCHANGE).isEqualTo(text("exchanges", "events", "name"));
        assertThat(RabbitMQConfig.RETRY_EXCHANGE).isEqualTo(text("exchanges", "retry", "name"));
        assertThat(RabbitMQConfig.DEAD_LETTER_EXCHANGE).isEqualTo(text("exchanges", "deadLetter", "name"));
        assertThat(RabbitMQConfig.AI_ANALYSIS_QUEUE).isEqualTo(text("queues", "aiAnalysis", "name"));
        assertThat(RabbitMQConfig.AI_BINDING_KEY).isEqualTo(text("queues", "aiAnalysis", "bindingKey"));
        assertThat(RabbitMQConfig.DEAD_LETTER_QUEUE).isEqualTo(text("queues", "deadLetter", "name"));
        assertThat(RabbitMQConfig.SKILL_UPDATED_KEY).isEqualTo(text("routingKeys", "SKILL_UPDATED"));
        assertThat(RabbitMQConfig.PROFILE_UPDATED_KEY).isEqualTo(text("routingKeys", "PROFILE_UPDATED"));
    }

    @Test
    @DisplayName("every queue is declared with exactly the contract's arguments")
    void queueArgumentsMatch() {
        Map<String, Queue> declared = queues();

        assertThat(declared.get(RabbitMQConfig.AI_ANALYSIS_QUEUE).getArguments())
                .isEqualTo(arguments(contract.at("/queues/aiAnalysis/arguments")));
        assertThat(declared.get(RabbitMQConfig.DEAD_LETTER_QUEUE).getArguments())
                .isEqualTo(arguments(contract.at("/queues/deadLetter/arguments")));

        JsonNode tiers = contract.at("/queues/retryTiers");
        assertThat(RabbitMQConfig.RETRY_TIERS).hasSize(tiers.size());
        for (int i = 0; i < tiers.size(); i++) {
            String name = tiers.get(i).get("name").asText();
            assertThat(RabbitMQConfig.RETRY_TIERS.get(i).queue()).as("tier %d, in order", i).isEqualTo(name);
            assertThat(declared.get(name).getArguments()).as("tier %s", name)
                    .isEqualTo(arguments(tiers.get(i).get("arguments")));
        }
    }

    @Test
    @DisplayName("every queue is quorum and durable")
    void everyQueueIsQuorum() {
        queues().values().forEach(q -> {
            assertThat(q.isDurable()).as("%s durable", q.getName()).isTrue();
            assertThat(q.getArguments()).as("%s quorum", q.getName()).containsEntry("x-queue-type", "quorum");
        });
    }

    @Test
    @DisplayName("no queue uses reject-publish-dlx, which a quorum queue accepts and does not honour")
    void noRejectPublishDlx() {
        // Measured on RabbitMQ 3.13.7: a quorum queue declared with it kept the NEW
        // message and dead-lettered the OLDEST when full. It reads as correct, which
        // is why it gets its own assertion.
        queues().values().forEach(q ->
                assertThat(q.getArguments().get("x-overflow")).as(q.getName()).isNotEqualTo("reject-publish-dlx"));
    }

    @Test
    @DisplayName("the main queue has no static retry routing key, so every retry tier is reachable")
    void mainQueueDoesNotRouteRetriesStatically() {
        // One static x-dead-letter-routing-key sends every nack to one tier and makes
        // the others unreachable. Retries are republished by the consumer to the tier
        // it chooses; the main queue dead-letters only to the DLQ.
        Map<String, Object> args = queues().get(RabbitMQConfig.AI_ANALYSIS_QUEUE).getArguments();
        assertThat(args).doesNotContainKey("x-dead-letter-routing-key");
        assertThat(args).containsEntry("x-dead-letter-exchange", RabbitMQConfig.DEAD_LETTER_EXCHANGE);
    }

    @Test
    @DisplayName("every binding joins a declared exchange to a declared queue")
    void bindingsAreClosed() {
        List<Declarable> all = RabbitMQConfig.topology().getDeclarables().stream().toList();
        List<String> exchanges = all.stream().filter(Exchange.class::isInstance)
                .map(d -> ((Exchange) d).getName()).toList();
        List<String> queues = all.stream().filter(Queue.class::isInstance)
                .map(d -> ((Queue) d).getName()).toList();
        List<Binding> bindings = all.stream().filter(Binding.class::isInstance).map(Binding.class::cast).toList();

        assertThat(bindings).isNotEmpty();
        bindings.forEach(b -> {
            assertThat(exchanges).as("binding source").contains(b.getExchange());
            assertThat(queues).as("binding destination").contains(b.getDestination());
        });
        assertThat(exchanges).containsExactlyInAnyOrder(
                RabbitMQConfig.EVENTS_EXCHANGE, RabbitMQConfig.RETRY_EXCHANGE, RabbitMQConfig.DEAD_LETTER_EXCHANGE);
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Queue> queues() {
        return RabbitMQConfig.topology().getDeclarables().stream()
                .filter(Queue.class::isInstance).map(Queue.class::cast)
                .collect(Collectors.toMap(Queue::getName, q -> q));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(JsonNode node) {
        return json.convertValue(node, Map.class);
    }

    private static String text(String... path) {
        JsonNode node = contract;
        for (String p : path) {
            node = node.get(p);
            assertThat(node).as("contract path %s", String.join(".", path)).isNotNull();
        }
        return node.asText();
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("contracts/amqp"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not find contracts/amqp above " + Path.of("").toAbsolutePath());
    }
}
