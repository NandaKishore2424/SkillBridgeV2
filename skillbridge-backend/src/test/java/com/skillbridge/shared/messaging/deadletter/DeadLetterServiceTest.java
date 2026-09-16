package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dead letters against a real PostgreSQL: storing them, and every way a decision
 * about one can go wrong.
 *
 * <p>Two kinds of test here need a database and nothing less. The hostile-message
 * inserts prove Postgres accepts what {@link DeadLetterMessage} produces — a unit
 * test can only prove the shaping. And the concurrency tests hold one transaction
 * open while another runs, which is the only way to show that two replays of one
 * row cannot both write an outbox event.
 */
@SpringBootTest
@IntegrationTest
class DeadLetterServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long ADMIN = 900L;
    /** A version 1 body: what was on the wire before the envelope, and may still be in a DLQ. */
    private static final String SKILL = "{\"eventType\":\"SKILL_UPDATED\",\"studentId\":31,\"collegeId\":1,"
            + "\"metadata\":{\"skillId\":7}}";
    private static final String ORIGINAL_ID = "3f1c2a9e-7b64-4d0f-9a51-6c2d8e4b7a10";
    /** A version 2 envelope: what the backend publishes now. */
    private static final String ENVELOPE = "{\"eventId\":\"" + ORIGINAL_ID + "\",\"eventType\":\"SKILL_UPDATED\","
            + "\"schemaVersion\":2,\"occurredAt\":\"2026-09-16T10:15:30.123Z\",\"aggregateType\":\"Student\","
            + "\"aggregateId\":\"31\",\"collegeId\":1,\"traceId\":null,\"payload\":{\"studentId\":31,\"skillId\":7}}";

    @Autowired private DeadLetterService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = dataSource.getConnection()) {
            // This class empties dead_letter_events. Refuse anything but a throwaway database.
            assertThat(c.getMetaData().getURL())
                    .as("this test deletes every dead letter; it must never run against Supabase")
                    .doesNotContain("supabase").doesNotContain("pooler");
        }
        tx = new TransactionTemplate(transactionManager);
        clean();
    }

    @AfterEach
    void tearDown() {
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM dead_letter_events");
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_type = ?", DeadLetterService.AGGREGATE_TYPE);
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("a message is stored once; the same message again is a no-op")
        void storesOnce() {
            DeadLetterMessage m = consumerDeadLetter(SKILL, "boom", UUID.randomUUID());

            assertThat(service.record(m)).isTrue();
            assertThat(service.record(m)).as("a redelivery").isFalse();

            assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events", Integer.class)).isEqualTo(1);
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM dead_letter_events");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("death_reason")).isEqualTo("consumer");
            assertThat(row.get("failure_reason")).isEqualTo("boom");
            assertThat(row.get("payload")).isEqualTo(SKILL);
            assertThat(row.get("event_id")).isEqualTo(m.eventId());
            assertThat(row.get("recorded_at")).isNotNull();
        }

        @Test
        @DisplayName("every hostile shape DeadLetterMessage produces, Postgres accepts")
        void hostileMessagesInsert() {
            // Each of these fails the insert if stored naively. A failed insert is not a
            // dropped message -- the recorder retries it -- but it blocks the whole DLQ.
            MessageProperties nasty = props(UUID.randomUUID());
            nasty.setHeader("nul", "a\u0000b");
            nasty.setHeader("lone", "\uD800");
            nasty.setHeader("nan", Double.NaN);
            nasty.setHeader("eventType", "T".repeat(500));
            nasty.setReceivedRoutingKey("k".repeat(1000));
            nasty.setHeader(RabbitMQConfig.FAILURE_REASON_HEADER, "r\u0000".repeat(4000));

            List<Message> messages = List.of(
                    new Message(new byte[]{(byte) 0xC3, (byte) 0x28}, props(UUID.randomUUID())),
                    new Message("{\"a\":\"x\u0000y\"}".getBytes(StandardCharsets.UTF_8), props(UUID.randomUUID())),
                    new Message("{\"a\":\"\\u0000\"}".getBytes(StandardCharsets.UTF_8), props(UUID.randomUUID())),
                    new Message("{\"a\":\"\\ud800\"}".getBytes(StandardCharsets.UTF_8), props(UUID.randomUUID())),
                    new Message(new byte[0], props(null)),
                    new Message(SKILL.getBytes(StandardCharsets.UTF_8), nasty));

            for (Message message : messages) {
                DeadLetterMessage m = DeadLetterMessage.from(message, JSON, Instant.now());
                assertThat(service.record(m)).as("inserted: %s", m.payload()).isTrue();
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM dead_letter_events", Integer.class))
                    .isEqualTo(messages.size());
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("writes an outbox event with the payload and the event type's routing key, and resolves the row")
        void replays() throws Exception {
            long id = stored(SKILL);

            UUID eventId = service.replay(id, ADMIN);

            Map<String, Object> outbox = jdbc.queryForMap(
                    "SELECT * FROM outbox_events WHERE event_id = ?", eventId);
            assertThat(outbox.get("status")).isEqualTo("PENDING");
            assertThat(outbox.get("event_type")).isEqualTo("SKILL_UPDATED");
            assertThat(outbox.get("routing_key")).isEqualTo(RabbitMQConfig.SKILL_UPDATED_KEY);
            assertThat(outbox.get("aggregate_type")).isEqualTo(DeadLetterService.AGGREGATE_TYPE);
            assertThat(outbox.get("aggregate_id")).isEqualTo(String.valueOf(id));
            assertThat(JSON.readTree(String.valueOf(outbox.get("payload"))))
                    .as("a version 1 body is sent as it was").isEqualTo(JSON.readTree(SKILL));
            assertThat(outbox.get("schema_version")).as("and under version 1").isEqualTo(1);

            Map<String, Object> row = row(id);
            assertThat(row.get("status")).isEqualTo("REPLAYED");
            assertThat(row.get("replay_event_id")).isEqualTo(eventId);
            assertThat(row.get("resolved_by")).isEqualTo(ADMIN);
            assertThat(row.get("resolved_at")).isNotNull();
        }

        @Test
        @DisplayName("an envelope is replayed in its own version, with a new event id that remembers the old one")
        void replaysAnEnvelope() throws Exception {
            long id = stored(ENVELOPE);

            UUID eventId = service.replay(id, ADMIN);

            Map<String, Object> outbox = jdbc.queryForMap(
                    "SELECT schema_version, payload::text AS payload FROM outbox_events WHERE event_id = ?", eventId);
            var sent = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree((String) outbox.get("payload"));
            assertThat(outbox.get("schema_version")).isEqualTo(2);
            assertThat(sent.get("eventId").asText())
                    .as("the consumer has already seen the old id; the replay must not look like a duplicate")
                    .isEqualTo(eventId.toString());
            assertThat(sent.get("replayOf").asText()).isEqualTo(ORIGINAL_ID);

            sent.remove(List.of("eventId", "replayOf"));
            var original = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(ENVELOPE);
            original.remove("eventId");
            assertThat(sent).as("everything else is what failed").isEqualTo(original);
        }

        @Test
        @DisplayName("half an envelope is not replayed: its version cannot be trusted")
        void refusesABrokenEnvelope() {
            long id = stored("{\"eventType\":\"SKILL_UPDATED\",\"schemaVersion\":\"2\",\"payload\":{\"studentId\":1}}");

            assertThat(service.get(id).replayBlocker()).isEqualTo(DeadLetterService.INVALID_ENVELOPE);
            assertThatThrownBy(() -> service.replay(id, ADMIN)).isInstanceOf(BusinessRuleException.class);
            assertThat(outboxRows()).isZero();
        }

        @Test
        @DisplayName("routes by event type, not by the key the message died under")
        void ignoresTheDeadLettersRoutingKey() {
            // A message that went round the retry tiers reaches the DLQ under a retry
            // queue's name. Replayed with that key, it would route to nothing.
            MessageProperties props = props(UUID.randomUUID());
            props.setReceivedRoutingKey(RabbitMQConfig.AI_ANALYSIS_QUEUE);
            props.setHeader(RabbitMQConfig.FAILURE_REASON_HEADER, "retries exhausted after 3 attempts");
            service.record(DeadLetterMessage.from(message(SKILL, props), JSON, Instant.now()));
            long id = onlyId();

            UUID eventId = service.replay(id, ADMIN);

            assertThat(jdbc.queryForObject("SELECT routing_key FROM outbox_events WHERE event_id = ?",
                    String.class, eventId)).isEqualTo(RabbitMQConfig.SKILL_UPDATED_KEY);
        }

        @Test
        @DisplayName("a second replay of the same row is a conflict, and writes nothing")
        void onlyOnce() {
            long id = stored(SKILL);
            service.replay(id, ADMIN);

            assertThatThrownBy(() -> service.replay(id, ADMIN)).isInstanceOf(ConflictException.class);
            assertThat(outboxRows()).isEqualTo(1);
        }

        @Test
        @DisplayName("a body that is not a replayable event is refused, and the row stays PENDING")
        void refusesWhatCannotBeReplayed() {
            long notJson = stored("not json {");
            long array = stored("[1,2,3]");
            long unknown = stored("{\"eventType\":\"SOMETHING_ELSE\"}");
            long noType = stored("{\"studentId\":1}");

            for (long id : new long[]{notJson, array, unknown, noType}) {
                assertThatThrownBy(() -> service.replay(id, ADMIN))
                        .as("dead letter %d", id)
                        .isInstanceOf(BusinessRuleException.class);
                assertThat(row(id).get("status")).isEqualTo("PENDING");
            }
            assertThat(outboxRows()).isZero();
            assertThat(service.get(notJson).replayBlocker()).isEqualTo(DeadLetterService.NOT_JSON_OBJECT);
            assertThat(service.get(unknown).replayBlocker()).isEqualTo(DeadLetterService.UNKNOWN_EVENT_TYPE);
        }

        @Test
        @DisplayName("two replays racing for one row: one wins, the other is refused, one outbox event")
        void racingReplays() throws Exception {
            long id = stored(SKILL);
            CountDownLatch firstHasReplayed = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            // The first replay runs inside a transaction that stays open until released,
            // so the second one runs while the first has written but not committed.
            CompletableFuture<UUID> first = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
                UUID eventId = service.replay(id, ADMIN);
                firstHasReplayed.countDown();
                await(releaseFirst);
                return eventId;
            }));
            assertThat(firstHasReplayed.await(10, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<UUID> second = CompletableFuture.supplyAsync(() -> service.replay(id, ADMIN + 1));
            Thread.sleep(500);
            assertThat(second).as("the second replay must wait for the first to finish").isNotDone();

            releaseFirst.countDown();
            UUID winner = first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ConflictException.class);

            assertThat(outboxRows()).as("exactly one outbox event").isEqualTo(1);
            assertThat(row(id).get("replay_event_id")).isEqualTo(winner);
            assertThat(row(id).get("resolved_by")).isEqualTo(ADMIN);
        }
    }

    @Nested
    @DisplayName("discard")
    class Discard {

        @Test
        @DisplayName("records who and why; afterwards neither a discard nor a replay is accepted")
        void discards() {
            long id = stored(SKILL);

            DeadLetterDTO discarded = service.discard(id, ADMIN, "  sent by hand in a drill  ");

            assertThat(discarded.status()).isEqualTo(DeadLetterStatus.DISCARDED);
            assertThat(discarded.resolutionNote()).isEqualTo("sent by hand in a drill");
            assertThat(discarded.resolvedBy()).isEqualTo(ADMIN);
            assertThat(discarded.replayBlocker()).isEqualTo("ALREADY_DISCARDED");
            assertThatThrownBy(() -> service.discard(id, ADMIN, "again")).isInstanceOf(ConflictException.class);
            assertThatThrownBy(() -> service.replay(id, ADMIN)).isInstanceOf(ConflictException.class);
            assertThat(outboxRows()).isZero();
        }

        @Test
        @DisplayName("a blank note is refused even if the controller's validation is bypassed")
        void needsANote() {
            long id = stored(SKILL);
            assertThatThrownBy(() -> service.discard(id, ADMIN, " ")).isInstanceOf(BusinessRuleException.class);
            assertThat(row(id).get("status")).isEqualTo("PENDING");
        }
    }

    @Nested
    @DisplayName("bulk replay")
    class Batch {

        @Test
        @DisplayName("a dry run reports what would happen and changes nothing")
        void dryRun() {
            long a = stored(SKILL);
            long bad = stored("not json");

            ReplayBatchResultDTO result = service.replayBatch(new ReplayBatchRequest(null, null, null, null), ADMIN);

            assertThat(result.dryRun()).isTrue();
            assertThat(result.replayed()).containsExactly(new ReplayBatchResultDTO.Replayed(a, null));
            assertThat(result.skipped()).containsExactly(
                    new ReplayBatchResultDTO.Skipped(bad, DeadLetterService.NOT_JSON_OBJECT));
            assertThat(outboxRows()).isZero();
            assertThat(row(a).get("status")).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("by event type: PENDING rows of that type, oldest first, up to the limit")
        void byEventType() {
            long first = stored(SKILL);
            long profile = stored("{\"eventType\":\"PROFILE_UPDATED\",\"studentId\":2,\"collegeId\":1}");
            long second = stored(SKILL);
            long third = stored(SKILL);
            long done = stored(SKILL);
            service.discard(done, ADMIN, "handled");
            // event_type comes from the message's eventType header, as the relay sets it.
            jdbc.update("UPDATE dead_letter_events SET event_type = 'PROFILE_UPDATED' WHERE id = ?", profile);

            ReplayBatchResultDTO result = service.replayBatch(
                    new ReplayBatchRequest(null, "SKILL_UPDATED", 2, false), ADMIN);

            assertThat(result.dryRun()).isFalse();
            assertThat(result.replayed()).extracting(ReplayBatchResultDTO.Replayed::id).containsExactly(first, second);
            assertThat(result.replayed()).allSatisfy(r -> assertThat(r.replayEventId()).isNotNull());
            assertThat(result.limitReached()).isTrue();
            assertThat(row(third).get("status")).as("past the limit").isEqualTo("PENDING");
            assertThat(row(profile).get("status")).as("another type").isEqualTo("PENDING");
            assertThat(outboxRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("by ids: says why each one it did not replay was left")
        void byIds() {
            long replayable = stored(SKILL);
            long already = stored(SKILL);
            service.replay(already, ADMIN);
            long missing = 987_654_321L;

            ReplayBatchResultDTO result = service.replayBatch(
                    new ReplayBatchRequest(List.of(replayable, already, missing), null, null, false), ADMIN);

            assertThat(result.replayed()).extracting(ReplayBatchResultDTO.Replayed::id).containsExactly(replayable);
            assertThat(result.skipped()).containsExactlyInAnyOrder(
                    new ReplayBatchResultDTO.Skipped(already, "ALREADY_REPLAYED"),
                    new ReplayBatchResultDTO.Skipped(missing, DeadLetterService.NOT_FOUND_OR_BUSY));
            assertThat(outboxRows()).isEqualTo(2);
        }

        @Test
        @DisplayName("two bulk replays at once take different rows, without waiting for each other")
        void concurrentBatchesDoNotOverlap() throws Exception {
            for (int i = 0; i < 5; i++) {
                stored(SKILL);
            }
            CountDownLatch firstHasRows = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);

            CompletableFuture<ReplayBatchResultDTO> first = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
                ReplayBatchResultDTO r = service.replayBatch(new ReplayBatchRequest(null, null, 2, false), ADMIN);
                firstHasRows.countDown();
                await(releaseFirst);
                return r;
            }));
            assertThat(firstHasRows.await(10, TimeUnit.SECONDS)).isTrue();

            // SKIP LOCKED: the second run passes over the first's rows instead of queueing behind them.
            ReplayBatchResultDTO second = CompletableFuture.supplyAsync(() ->
                    service.replayBatch(new ReplayBatchRequest(null, null, 10, false), ADMIN + 1))
                    .get(5, TimeUnit.SECONDS);
            releaseFirst.countDown();
            ReplayBatchResultDTO firstResult = first.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.replayed()).hasSize(2);
            assertThat(second.replayed()).hasSize(3);
            assertThat(second.replayed()).extracting(ReplayBatchResultDTO.Replayed::id)
                    .doesNotContainAnyElementsOf(firstResult.replayed().stream()
                            .map(ReplayBatchResultDTO.Replayed::id).toList());
            assertThat(outboxRows()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("reading, purging, and what the schema refuses")
    class Housekeeping {

        @Test
        @DisplayName("the list is newest first, filters by type, and leaves the body out; get puts it in")
        void listAndGet() {
            long older = stored(SKILL);
            long newer = stored(SKILL);

            var page = service.list(DeadLetterStatus.PENDING, "SKILL_UPDATED", 0, 20);

            assertThat(page.getItems()).extracting(DeadLetterDTO::id).containsExactly(newer, older);
            assertThat(page.getItems()).allSatisfy(d -> {
                assertThat(d.payload()).isNull();
                assertThat(d.replayBlocker()).isNull();
            });
            assertThat(service.list(DeadLetterStatus.PENDING, "PROFILE_UPDATED", 0, 20).getItems()).isEmpty();
            assertThat(service.list(DeadLetterStatus.REPLAYED, null, 0, 20).getItems()).isEmpty();

            DeadLetterDTO detail = service.get(older);
            assertThat(detail.payload()).isEqualTo(SKILL);
            assertThat(detail.headers().get("traceId").asText()).isEqualTo("trace-dl");
        }

        @Test
        @DisplayName("the purge removes resolved rows past the cutoff and never a PENDING one")
        void purge() {
            long oldPending = stored(SKILL);
            long oldReplayed = stored(SKILL);
            long newDiscarded = stored(SKILL);
            service.replay(oldReplayed, ADMIN);
            service.discard(newDiscarded, ADMIN, "noise");
            jdbc.update("UPDATE dead_letter_events SET recorded_at = now() - interval '90 days', "
                    + "resolved_at = CASE WHEN status = 'PENDING' THEN NULL ELSE now() - interval '60 days' END "
                    + "WHERE id IN (?, ?)", oldPending, oldReplayed);

            int deleted = service.purgeResolvedBefore(Instant.now().minus(Duration.ofDays(30)));

            assertThat(deleted).isEqualTo(1);
            assertThat(jdbc.queryForList("SELECT id FROM dead_letter_events ORDER BY id", Long.class))
                    .containsExactly(oldPending, newDiscarded);
        }

        @Test
        @DisplayName("the schema refuses a replay that names no event, and a resolution that disagrees with the status")
        void constraints() {
            long id = stored(SKILL);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dead_letter_events SET status = 'REPLAYED', resolved_at = now() WHERE id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_dead_letter_replayed");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE dead_letter_events SET status = 'DISCARDED' WHERE id = ?", id))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_dead_letter_resolved");
        }
    }

    // ---------------------------------------------------------------- helpers

    private long stored(String body) {
        assertThat(service.record(consumerDeadLetter(body, "failed", UUID.randomUUID()))).isTrue();
        return jdbc.queryForObject("SELECT max(id) FROM dead_letter_events", Long.class);
    }

    private long onlyId() {
        return jdbc.queryForObject("SELECT id FROM dead_letter_events", Long.class);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM dead_letter_events WHERE id = ?", id);
    }

    private int outboxRows() {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_type = ?",
                Integer.class, DeadLetterService.AGGREGATE_TYPE);
    }

    private static DeadLetterMessage consumerDeadLetter(String body, String reason, UUID eventId) {
        MessageProperties props = props(eventId);
        props.setHeader(RabbitMQConfig.FAILURE_REASON_HEADER, reason);
        props.setHeader(RabbitMQConfig.FAILED_AT_HEADER, System.currentTimeMillis());
        props.setHeader(RabbitMQConfig.FAILED_QUEUE_HEADER, RabbitMQConfig.AI_ANALYSIS_QUEUE);
        return DeadLetterMessage.from(message(body, props), JSON, Instant.now());
    }

    private static MessageProperties props(UUID eventId) {
        MessageProperties props = new MessageProperties();
        if (eventId != null) {
            props.setMessageId(eventId.toString());
        }
        props.setReceivedRoutingKey(RabbitMQConfig.SKILL_UPDATED_KEY);
        props.setHeader("eventType", "SKILL_UPDATED");
        props.setHeader("traceId", "trace-dl");
        return props;
    }

    private static Message message(String body, MessageProperties props) {
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException(new TimeoutException("never released"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
