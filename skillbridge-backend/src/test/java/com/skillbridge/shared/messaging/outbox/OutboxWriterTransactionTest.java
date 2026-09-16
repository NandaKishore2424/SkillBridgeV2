package com.skillbridge.shared.messaging.outbox;

import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.shared.messaging.EventType;
import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox's central promise: an event exists if and only if its transaction
 * committed.
 *
 * <p>Against a real PostgreSQL and a real transaction manager, because the
 * promise is entirely about transaction boundaries and a mocked repository has
 * none. The broker is not involved at all — that is the point of the pattern.
 *
 * <p>Rows are marked with their own aggregate type and removed afterwards, so
 * this touches nothing it did not create.
 */
@SpringBootTest
@IntegrationTest
class OutboxWriterTransactionTest {

    private static final String MARKER = "OutboxWriterTransactionTest";

    @Autowired private OutboxWriter writer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        clean();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        clean();
    }

    private void clean() {
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_type = ?", MARKER);
    }

    @Test
    @DisplayName("a committed transaction leaves exactly one PENDING event")
    void commitWritesTheEvent() {
        UUID eventId = tx.execute(status ->
                writer.write(EventType.SKILL_UPDATED, MARKER, 42L, 1L, new EventType.SkillUpdated(42, 7)));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, event_type, routing_key, status, attempts, schema_version, payload::text AS payload "
                        + "FROM outbox_events WHERE aggregate_type = ?", MARKER);

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("event_id")).isEqualTo(eventId);
        assertThat(row.get("event_type")).isEqualTo("SKILL_UPDATED");
        assertThat(row.get("routing_key")).isEqualTo(RabbitMQConfig.SKILL_UPDATED_KEY);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("schema_version")).isEqualTo(EventType.SKILL_UPDATED.schemaVersion());
        assertThat((String) row.get("payload"))
                .contains("\"eventId\": \"" + eventId + "\"")
                .contains("\"skillId\": 7");
    }

    @Test
    @DisplayName("a rolled-back transaction leaves no event: the dual-write problem, closed")
    void rollbackWritesNothing() {
        // The failure the outbox exists for. Before it, the publish went out on
        // commit via a callback -- correct on rollback -- but a broker outage lost
        // the event. The pre-2026-09-09 version published mid-transaction and would
        // have announced this change to the AI service before it was undone.
        tx.executeWithoutResult(status -> {
            writer.write(EventType.SKILL_UPDATED, MARKER, 42L, 1L, new EventType.SkillUpdated(42, 7));
            status.setRollbackOnly();
        });

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_type = ?", Integer.class, MARKER);
        assertThat(count).as("the event must roll back with the change it describes").isZero();
    }

    @Test
    @DisplayName("writing outside a transaction fails loudly instead of committing alone")
    void outsideATransactionIsRefused() {
        // MANDATORY. A standalone insert would commit whether or not any business
        // change did -- the dual-write problem again, wearing a different hat.
        assertThatThrownBy(() ->
                writer.write(EventType.SKILL_UPDATED, MARKER, 42L, 1L, new EventType.SkillUpdated(42, 7)))
                .isInstanceOf(IllegalTransactionStateException.class);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_type = ?", Integer.class, MARKER);
        assertThat(count).isZero();
    }

    @Test
    @DisplayName("the request's trace id travels with the event")
    void traceIdIsCarried() {
        MDC.put("traceId", "trace-abc-123");

        tx.execute(status -> writer.write(EventType.PROFILE_UPDATED, MARKER, 42L, 1L, new EventType.ProfileUpdated(42)));

        String headers = jdbc.queryForObject(
                "SELECT headers::text FROM outbox_events WHERE aggregate_type = ?", String.class, MARKER);
        assertThat(headers).contains("trace-abc-123");
    }
}
