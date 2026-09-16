package com.skillbridge.shared.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telling an envelope from a version 1 body, and re-addressing one for a replay.
 *
 * <p>Both decide what a replay sends and under which version, and a mistake in
 * either sends an event the consumer will dead-letter again -- or, worse, one it
 * will acknowledge as a duplicate of the event that failed.
 */
class EventEnvelopeTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENVELOPE = """
            {"eventId":"3f1c2a9e-7b64-4d0f-9a51-6c2d8e4b7a10","eventType":"SKILL_UPDATED","schemaVersion":2,
             "occurredAt":"2026-09-16T10:15:30.123Z","aggregateType":"Student","aggregateId":"31",
             "collegeId":1,"traceId":null,"payload":{"studentId":31,"skillId":7}}""";
    private static final String VERSION_ONE = """
            {"eventType":"SKILL_UPDATED","studentId":31,"collegeId":1,"metadata":{"skillId":7}}""";

    @Test
    @DisplayName("an envelope reports its own version; a body without one is version 1")
    void versions() throws Exception {
        assertThat(EventEnvelope.isEnvelope(tree(ENVELOPE))).isTrue();
        assertThat(EventEnvelope.schemaVersionOf(tree(ENVELOPE))).isEqualTo(2);
        assertThat(EventEnvelope.isEnvelope(tree(VERSION_ONE))).isFalse();
        assertThat(EventEnvelope.claimsToBeEnvelope(tree(VERSION_ONE))).isFalse();
        assertThat(EventEnvelope.schemaVersionOf(tree(VERSION_ONE))).isEqualTo(1);
    }

    @Test
    @DisplayName("half an envelope claims to be one but is not, so a replay can refuse it")
    void brokenEnvelopes() throws Exception {
        for (String broken : List.of(
                "{\"eventType\":\"SKILL_UPDATED\",\"schemaVersion\":\"2\",\"payload\":{}}",
                "{\"eventType\":\"SKILL_UPDATED\",\"schemaVersion\":2.5,\"payload\":{}}",
                "{\"eventType\":\"SKILL_UPDATED\",\"schemaVersion\":0,\"payload\":{}}",
                "{\"eventType\":\"SKILL_UPDATED\",\"schemaVersion\":2,\"payload\":[]}",
                "{\"eventType\":\"SKILL_UPDATED\",\"payload\":{}}",
                "{\"schemaVersion\":2,\"payload\":{}}")) {
            JsonNode body = tree(broken);
            assertThat(EventEnvelope.claimsToBeEnvelope(body)).as(broken).isTrue();
            assertThat(EventEnvelope.isEnvelope(body)).as(broken).isFalse();
        }
    }

    @Test
    @DisplayName("a replayed envelope has a new id, remembers the old one, and is otherwise unchanged")
    void replayReaddresses() throws Exception {
        JsonNode original = tree(ENVELOPE);
        UUID newId = UUID.randomUUID();

        JsonNode replay = EventEnvelope.forReplay(original, newId);

        assertThat(replay.get("eventId").asText()).isEqualTo(newId.toString());
        assertThat(replay.get("replayOf").asText()).isEqualTo("3f1c2a9e-7b64-4d0f-9a51-6c2d8e4b7a10");
        var withoutIds = replay.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) withoutIds).remove(List.of("eventId", "replayOf"));
        var originalWithoutId = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) originalWithoutId).remove("eventId");
        assertThat(withoutIds).as("payload, version and occurredAt are what failed").isEqualTo(originalWithoutId);
        assertThat(original.get("eventId").asText()).as("the stored body is not mutated")
                .isEqualTo("3f1c2a9e-7b64-4d0f-9a51-6c2d8e4b7a10");
    }

    @Test
    @DisplayName("a version 1 body is replayed as it was; it has nowhere to carry an id")
    void versionOneIsUnchanged() throws Exception {
        JsonNode body = tree(VERSION_ONE);
        assertThat(EventEnvelope.forReplay(body, UUID.randomUUID())).isEqualTo(tree(VERSION_ONE));
    }

    @Test
    @DisplayName("a fresh envelope serialises without replayOf, and with it on a replay")
    void replayOfOnlyWhenSet() throws Exception {
        UUID id = UUID.randomUUID();
        String fresh = JSON.writeValueAsString(new EventEnvelope<>(id, "PROFILE_UPDATED", 2,
                "2026-09-16T10:00:00Z", "Student", "31", 1L, null, null, new EventType.ProfileUpdated(31)));
        String replay = JSON.writeValueAsString(new EventEnvelope<>(id, "PROFILE_UPDATED", 2,
                "2026-09-16T10:00:00Z", "Student", "31", 1L, null, UUID.randomUUID(),
                new EventType.ProfileUpdated(31)));

        assertThat(tree(fresh).has("replayOf")).isFalse();
        assertThat(tree(fresh).has("traceId")).as("a null trace id is written, as the schema requires").isTrue();
        assertThat(tree(replay).has("replayOf")).isTrue();
    }

    private static JsonNode tree(String json) throws Exception {
        return JSON.readTree(json);
    }
}
