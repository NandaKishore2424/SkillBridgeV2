package com.skillbridge.shared.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.skillbridge.shared.messaging.outbox.OutboxEvent;
import com.skillbridge.shared.messaging.outbox.OutboxEventRepository;
import com.skillbridge.shared.messaging.outbox.OutboxWriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The producer's half of the AIEvent contract.
 *
 * <p>Validates the payload {@link AIEventPublisher} records in the outbox against
 * {@code contracts/ai-events/v1/ai-event.schema.json}. That stored payload is
 * what reaches the broker byte for byte: {@code OutboxRelay} sends the text it
 * finds rather than re-serialising it, which {@code OutboxRelayBrokerTest} asserts
 * against a real RabbitMQ. The Python consumer is validated against the same
 * schema by {@code skillbridge-ai-service/tests/test_ai_event_contract.py}.
 *
 * <h2>What this caught once already</h2>
 *
 * <p>{@code AIEvent.metadata} is typed {@code Object}. It used to carry a bare
 * {@code Long} for SKILL_UPDATED and an explicit {@code null} for
 * PROFILE_UPDATED; the consumer's {@code .get()} raised on both, the message was
 * nacked without requeue, and with no dead-letter queue every AI event ever
 * published was discarded. Each end was green about its own idea of the format.
 * <b>When two components share a format, the format has to be an artifact both
 * are checked against.</b>
 *
 * <p>No Spring context. {@code MANDATORY} propagation on the writer is not
 * exercised here — {@code OutboxWriterTransactionTest} does that against a real transaction.
 * A plain {@code ObjectMapper} stands in for Spring's: nothing in this payload
 * depends on the modules or inclusion settings Boot adds.
 */
class AiEventContractTest {

    private static JsonSchema schema;
    private static final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void loadContract() throws Exception {
        Path contract = repositoryRoot().resolve("contracts/ai-events/v1/ai-event.schema.json");
        assertThat(contract)
                .as("the contract is a committed artifact, not something a test invents")
                .isReadable();
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(contract));
    }

    @Test
    @DisplayName("a recorded SKILL_UPDATED payload satisfies the contract")
    void skillUpdatedMatchesTheContract() throws Exception {
        JsonNode recorded = recordAndRead(p -> p.publishSkillUpdated(31L, 1L, 7L));

        assertThat(schema.validate(recorded)).as("SKILL_UPDATED must validate; %s", recorded).isEmpty();
    }

    @Test
    @DisplayName("a recorded PROFILE_UPDATED payload satisfies the contract")
    void profileUpdatedMatchesTheContract() throws Exception {
        JsonNode recorded = recordAndRead(p -> p.publishProfileUpdated(31L, 1L));

        assertThat(schema.validate(recorded)).as("PROFILE_UPDATED must validate; %s", recorded).isEmpty();
    }

    @Test
    @DisplayName("metadata is an object, never the bare scalar that used to be sent")
    void skillUpdatedSendsMetadataAsAnObject() throws Exception {
        JsonNode recorded = recordAndRead(p -> p.publishSkillUpdated(31L, 1L, 7L));

        assertThat(recorded.get("metadata").isObject())
                .as("a scalar here raises AttributeError in the consumer and the event is dropped")
                .isTrue();
        assertThat(recorded.get("metadata").get("skillId").asLong()).isEqualTo(7L);
    }

    @Test
    @DisplayName("the field names are the ones the consumer reads")
    void fieldNamesAreStable() throws Exception {
        JsonNode recorded = recordAndRead(p -> p.publishSkillUpdated(31L, 1L, 7L));

        assertThat(recorded.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventType", "studentId", "collegeId", "metadata");
    }

    @Test
    @DisplayName("the committed examples are what the producer really records")
    void examplesMatchTheProducer() throws Exception {
        JsonNode recorded = recordAndRead(p -> p.publishSkillUpdated(31L, 1L, 7L));
        JsonNode example = json.readTree(Files.readString(
                repositoryRoot().resolve("contracts/ai-events/v1/skill-updated.example.json")));

        assertThat(recorded).isEqualTo(example);
    }

    @Test
    @DisplayName("the schema rejects the shape that used to be sent")
    void theOldShapeIsRejected() throws Exception {
        JsonNode oldShape = json.readTree("""
                {"eventType":"SKILL_UPDATED","studentId":31,"collegeId":1,"metadata":7}
                """);

        Set<ValidationMessage> violations = schema.validate(oldShape);
        assertThat(violations).as("a scalar metadata must fail validation").isNotEmpty();
    }

    /** Runs a publish and returns the payload the outbox row would carry. */
    private JsonNode recordAndRead(Consumer<AIEventPublisher> publish) throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        AIEventPublisher publisher = new AIEventPublisher(new OutboxWriter(repository, json));

        publish.accept(publisher);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(saved.capture());
        // Parsed from the stored TEXT, which is what the relay publishes.
        return json.readTree(saved.getValue().getPayload());
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
