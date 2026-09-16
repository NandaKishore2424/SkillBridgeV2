package com.skillbridge.shared.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.shared.messaging.outbox.OutboxEvent;
import com.skillbridge.shared.messaging.outbox.OutboxEventRepository;
import com.skillbridge.shared.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The producer's half of the AI event contract.
 *
 * <p>Validates what {@link AIEventPublisher} records in the outbox against
 * {@code contracts/ai-events/v2/ai-event.schema.json}, and the producer's versions
 * against {@code contracts/ai-events/versions.json}. That stored payload is what
 * reaches the broker byte for byte — {@code OutboxRelay} sends the text it finds,
 * which {@code OutboxRelayBrokerTest} asserts against a real RabbitMQ. The Python
 * consumer is checked against the same files by
 * {@code skillbridge-ai-service/tests/test_ai_event_contract.py}.
 *
 * <h2>What this caught once already</h2>
 *
 * <p>Version 1's {@code metadata} was typed {@code Object}. It carried a bare
 * {@code Long} for SKILL_UPDATED and an explicit {@code null} for PROFILE_UPDATED;
 * the consumer's {@code .get()} raised on both, and with no dead-letter queue every
 * AI event ever published was discarded. Each end was green about its own idea of
 * the format. <b>When two components share a format, the format has to be an
 * artifact both are checked against</b> — and since version 2, so does the question
 * of which version is on the wire.
 *
 * <p>No Spring context, and a plain {@code ObjectMapper}: nothing in the envelope
 * may depend on the modules Boot registers, which is why {@code occurredAt} is text.
 */
class AiEventContractTest {

    private static final ObjectMapper json = new ObjectMapper();
    private static Path root;
    private static JsonSchema v1;
    private static JsonSchema v2;
    private static JsonNode versions;

    @BeforeAll
    static void loadContract() throws Exception {
        root = repositoryRoot();
        v1 = schema("contracts/ai-events/v1/ai-event.schema.json");
        v2 = schema("contracts/ai-events/v2/ai-event.schema.json");
        versions = json.readTree(Files.readString(root.resolve("contracts/ai-events/versions.json")));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Nested
    @DisplayName("what the producer records")
    class Produced {

        @Test
        @DisplayName("a SKILL_UPDATED envelope satisfies the version 2 schema")
        void skillUpdatedValidates() {
            JsonNode recorded = record(p -> p.publishSkillUpdated(31L, 1L, 7L)).body();
            assertThat(v2.validate(recorded)).as("%s", recorded).isEmpty();
        }

        @Test
        @DisplayName("a PROFILE_UPDATED envelope satisfies the version 2 schema")
        void profileUpdatedValidates() {
            JsonNode recorded = record(p -> p.publishProfileUpdated(31L, 1L)).body();
            assertThat(v2.validate(recorded)).as("%s", recorded).isEmpty();
        }

        @Test
        @DisplayName("the envelope, the outbox row and the AMQP headers agree on id, type and version")
        void oneIdOneVersionEverywhere() {
            Recorded recorded = record(p -> p.publishSkillUpdated(31L, 1L, 7L));
            JsonNode body = recorded.body();
            OutboxEvent row = recorded.row();

            // The relay sets message_id and the eventId/schemaVersion headers from the row.
            assertThat(body.get("eventId").asText()).isEqualTo(row.getEventId().toString());
            assertThat(body.get("eventType").asText()).isEqualTo(row.getEventType());
            assertThat(body.get("schemaVersion").asInt()).isEqualTo(row.getSchemaVersion());
            assertThat(row.getRoutingKey()).isEqualTo(RabbitMQConfig.SKILL_UPDATED_KEY);
            assertThat(body.get("aggregateType").asText()).isEqualTo(row.getAggregateType());
            assertThat(body.get("aggregateId").asText()).isEqualTo(row.getAggregateId()).isEqualTo("31");
        }

        @Test
        @DisplayName("the request's trace id is in the envelope, not only in a header")
        void traceIdInTheBody() {
            MDC.put("traceId", "trace-abc-123");
            assertThat(record(p -> p.publishProfileUpdated(31L, 1L)).body().get("traceId").asText())
                    .isEqualTo("trace-abc-123");
        }

        @Test
        @DisplayName("the committed examples are what the producer really records, apart from ids and times")
        void examplesMatchTheProducer() throws Exception {
            MDC.put("traceId", "4bf92f3577b34da6");
            assertThat(volatileRemoved(record(p -> p.publishSkillUpdated(31L, 1L, 7L)).body()))
                    .isEqualTo(volatileRemoved(example("v2/skill-updated.example.json")));

            MDC.clear();
            assertThat(volatileRemoved(record(p -> p.publishProfileUpdated(31L, 1L)).body()))
                    .isEqualTo(volatileRemoved(example("v2/profile-updated.example.json")));
        }

        @Test
        @DisplayName("the top-level field names are the envelope's, and a fresh event is not a replay")
        void fieldNamesAreStable() {
            JsonNode recorded = record(p -> p.publishSkillUpdated(31L, 1L, 7L)).body();
            assertThat(recorded.fieldNames()).toIterable().containsExactly(
                    "eventId", "eventType", "schemaVersion", "occurredAt", "aggregateType",
                    "aggregateId", "collegeId", "traceId", "payload");
        }
    }

    @Nested
    @DisplayName("versions")
    class Versions {

        @Test
        @DisplayName("every event type is produced at the version versions.json names")
        void producedVersionsMatch() {
            for (EventType type : EventType.values()) {
                assertThat(type.schemaVersion()).as("%s", type)
                        .isEqualTo(versions.at("/eventTypes/" + type.name() + "/produced").asInt(-1));
            }
        }

        @Test
        @DisplayName("nothing is produced in a version the consumer does not accept")
        void producedIsAccepted() {
            // The rule a version bump must not break: move the consumer first.
            versions.get("eventTypes").fields().forEachRemaining(e -> {
                int produced = e.getValue().get("produced").asInt();
                List<Integer> accepted = new ArrayList<>();
                e.getValue().get("accepted").forEach(v -> accepted.add(v.asInt()));
                assertThat(accepted).as("%s produces v%d", e.getKey(), produced).contains(produced);
            });
        }

        @Test
        @DisplayName("the event types agree everywhere they are named")
        void eventTypesAgree() {
            Set<String> produced = Arrays.stream(EventType.values()).map(Enum::name)
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> inVersions = new TreeSet<>();
            versions.get("eventTypes").fieldNames().forEachRemaining(inVersions::add);
            Set<String> inSchema = new TreeSet<>();
            schemaJson("contracts/ai-events/v2/ai-event.schema.json").at("/properties/eventType/enum")
                    .forEach(t -> inSchema.add(t.asText()));

            assertThat(produced).isEqualTo(inVersions).isEqualTo(inSchema)
                    .isEqualTo(new TreeSet<>(RabbitMQConfig.ROUTING_KEYS.keySet()));
            for (EventType type : EventType.values()) {
                assertThat(type.routingKey()).as("%s routing key", type)
                        .isEqualTo(RabbitMQConfig.ROUTING_KEYS.get(type.name()));
            }
        }

        @Test
        @DisplayName("every accepted version has a schema, and its examples satisfy it")
        void everyAcceptedVersionIsDefined() throws Exception {
            Set<Integer> accepted = new TreeSet<>();
            versions.get("eventTypes").forEach(t -> t.get("accepted").forEach(v -> accepted.add(v.asInt())));
            for (int version : accepted) {
                Path dir = root.resolve("contracts/ai-events/v" + version);
                JsonSchema schema = schema("contracts/ai-events/v" + version + "/ai-event.schema.json");
                try (var examples = Files.list(dir)) {
                    List<Path> files = examples.filter(f -> f.getFileName().toString().endsWith(".example.json")).toList();
                    assertThat(files).as("v%d has examples", version).isNotEmpty();
                    for (Path file : files) {
                        assertThat(schema.validate(json.readTree(Files.readString(file))))
                                .as("%s", file.getFileName()).isEmpty();
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("version 1, which consumers still accept")
    class VersionOne {

        @Test
        @DisplayName("a version 2 envelope is not a valid version 1 event, and vice versa")
        void versionsAreDistinct() throws Exception {
            assertThat(v1.validate(example("v2/skill-updated.example.json"))).isNotEmpty();
            assertThat(v2.validate(example("v1/skill-updated.example.json"))).isNotEmpty();
        }

        @Test
        @DisplayName("the version 1 schema still rejects the scalar metadata that broke every event")
        void theOldShapeIsRejected() throws Exception {
            JsonNode oldShape = json.readTree("""
                    {"eventType":"SKILL_UPDATED","studentId":31,"collegeId":1,"metadata":7}
                    """);
            Set<ValidationMessage> violations = v1.validate(oldShape);
            assertThat(violations).as("a scalar metadata must fail validation").isNotEmpty();
        }
    }

    // ---------------------------------------------------------------- helpers

    record Recorded(OutboxEvent row, JsonNode body) {
    }

    /** Runs a publish and returns the outbox row, and its payload as the relay will send it. */
    private static Recorded record(Consumer<AIEventPublisher> publish) {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        publish.accept(new AIEventPublisher(new OutboxWriter(repository, json)));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        try {
            return new Recorded(saved.getValue(), json.readTree(saved.getValue().getPayload()));
        } catch (Exception e) {
            throw new AssertionError("the stored payload is not JSON", e);
        }
    }

    /** The fields that differ on every event. Everything else must match the example exactly. */
    private static JsonNode volatileRemoved(JsonNode envelope) {
        ObjectNode copy = envelope.deepCopy();
        assertThat(copy.remove("eventId")).as("eventId").isNotNull();
        assertThat(copy.remove("occurredAt")).as("occurredAt").isNotNull();
        return copy;
    }

    private static JsonNode example(String name) throws Exception {
        return json.readTree(Files.readString(root.resolve("contracts/ai-events/" + name)));
    }

    private static JsonSchema schema(String path) {
        Path file = root.resolve(path);
        assertThat(file).as("the contract is a committed artifact, not something a test invents").isReadable();
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schemaJson(path));
    }

    private static JsonNode schemaJson(String path) {
        try {
            return json.readTree(Files.readString(root.resolve(path)));
        } catch (Exception e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("contracts/ai-events"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not find contracts/ above " + Path.of("").toAbsolutePath());
    }
}
