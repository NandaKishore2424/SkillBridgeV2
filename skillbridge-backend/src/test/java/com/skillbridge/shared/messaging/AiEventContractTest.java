package com.skillbridge.shared.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The producer's half of the AIEvent contract.
 *
 * <p>Validates what {@link AIEventPublisher} <i>actually sends</i> — captured
 * from a mocked {@code RabbitTemplate}, not hand-built here — against
 * {@code contracts/ai-events/v1/ai-event.schema.json}. The Python consumer is
 * validated against the same file by
 * {@code skillbridge-ai-service/tests/test_ai_event_contract.py}, so neither
 * side can change the wire format alone.
 *
 * <h2>What this would have caught</h2>
 *
 * <p>{@code AIEvent.metadata} is typed {@code Object}, and
 * {@code publishSkillUpdated} put a bare {@code Long} in it. That serialises as
 * a JSON number. The consumer does {@code metadata.get("skills", [])}, which on
 * a number raises {@code AttributeError}; {@code _rabbitmq_callback} nacks with
 * {@code requeue=False}; there is no dead-letter queue. <b>Every SKILL_UPDATED
 * event ever published was discarded</b>, announced only by a print to stdout.
 *
 * <p>{@code PROFILE_UPDATED} failed the same way for a different reason: it sent
 * an explicit {@code null}, and {@code payload.get("metadata", {})} returns
 * {@code None} for a present-but-null key — a default only covers a
 * <i>missing</i> one.
 *
 * <p>Neither was visible from either side alone. The Java tests asserted a
 * message was sent; the Python code had no tests. <b>When two components share
 * a format, the format needs to be an artifact both are checked against</b> —
 * testing each end against its own idea of the contract is how both ends stay
 * green while the system does nothing.
 *
 * <p>No Spring context: a mocked template and a manual transaction
 * synchronisation, so this runs in the fast tier.
 */
class AiEventContractTest {

    private static JsonSchema schema;
    private static ObjectMapper json;

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @BeforeAll
    static void loadContract() throws Exception {
        json = new ObjectMapper();
        Path contract = repositoryRoot()
                .resolve("contracts/ai-events/v1/ai-event.schema.json");
        assertThat(contract)
                .as("the contract is a committed artifact, not something a test invents")
                .isReadable();
        schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(contract));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("a published SKILL_UPDATED event satisfies the contract")
    void skillUpdatedMatchesTheContract() {
        JsonNode published = publishAndCapture(
                publisher -> publisher.publishSkillUpdated(31L, 1L, 7L));

        assertThat(violations(published))
                .as("SKILL_UPDATED must validate; %s", published)
                .isEmpty();
    }

    @Test
    @DisplayName("a published PROFILE_UPDATED event satisfies the contract")
    void profileUpdatedMatchesTheContract() {
        JsonNode published = publishAndCapture(
                publisher -> publisher.publishProfileUpdated(31L, 1L));

        assertThat(violations(published))
                .as("PROFILE_UPDATED must validate; %s", published)
                .isEmpty();
    }

    @Test
    @DisplayName("metadata is an object, never the bare scalar that used to be sent")
    void skillUpdatedSendsMetadataAsAnObject() {
        JsonNode published = publishAndCapture(
                publisher -> publisher.publishSkillUpdated(31L, 1L, 7L));

        // The specific regression. `metadata` is typed Object, so nothing in
        // Java stops a scalar going back in; the schema and this assertion are
        // what does.
        assertThat(published.get("metadata").isObject())
                .as("a scalar here raises AttributeError in the consumer and the event is dropped")
                .isTrue();
        assertThat(published.get("metadata").get("skillId").asLong()).isEqualTo(7L);
    }

    @Test
    @DisplayName("the field names are the ones the consumer reads")
    void fieldNamesAreStable() {
        JsonNode published = publishAndCapture(
                publisher -> publisher.publishSkillUpdated(31L, 1L, 7L));

        // Java serialises record component names. Renaming a component is a
        // silent change: the consumer's payload.get(...) returns None rather
        // than raising, and the event is skipped.
        assertThat(published.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventType", "studentId", "collegeId", "metadata");
    }

    @Test
    @DisplayName("the committed examples are what the publisher really produces")
    void examplesMatchTheProducer() throws Exception {
        JsonNode published = publishAndCapture(
                publisher -> publisher.publishSkillUpdated(31L, 1L, 7L));
        JsonNode example = json.readTree(Files.readString(repositoryRoot()
                .resolve("contracts/ai-events/v1/skill-updated.example.json")));

        // The example is what the Python test feeds its dispatcher. If it drifts
        // from what Java emits, the consumer is being tested against fiction.
        assertThat(published).isEqualTo(example);
    }

    @Test
    @DisplayName("the schema rejects the shape that used to be sent")
    void theOldShapeIsRejected() throws Exception {
        // Confirms the contract has teeth rather than accepting anything: the
        // exact payload that was being published before 2026-09-13.
        JsonNode oldShape = json.readTree("""
                {"eventType":"SKILL_UPDATED","studentId":31,"collegeId":1,"metadata":7}
                """);

        assertThat(violations(oldShape))
                .as("a scalar metadata must fail validation")
                .isNotEmpty();
    }

    // ---------------------------------------------------------------- utils

    /** Runs a publish, commits the synchronisation, and returns what was sent. */
    private JsonNode publishAndCapture(java.util.function.Consumer<AIEventPublisher> publish) {
        AIEventPublisher publisher = new AIEventPublisher(rabbitTemplate, Runnable::run);
        TransactionSynchronizationManager.initSynchronization();
        publish.accept(publisher);
        // Publishing is deferred to afterCommit, so nothing is sent until this.
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        ArgumentCaptor<Object> captured = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), captured.capture());

        // Serialise, then re-parse. `valueToTree` keeps Java's numeric types, so
        // a Long 7 becomes a LongNode and the identical 7 read from the example
        // file is an IntNode -- equal JSON, unequal nodes, and a failure that
        // prints two byte-identical strings. Going through text is also what
        // actually reaches the broker.
        try {
            return json.readTree(json.writeValueAsString(captured.getValue()));
        } catch (Exception e) {
            throw new IllegalStateException("the published event is not serialisable", e);
        }
    }

    private Set<ValidationMessage> violations(JsonNode node) {
        return schema.validate(node);
    }

    /** The directory holding {@code contracts/}, found by walking up. */
    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && candidate != null; depth++) {
            if (Files.isDirectory(candidate.resolve("contracts/ai-events"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not find contracts/ above "
                + Path.of("").toAbsolutePath());
    }
}
