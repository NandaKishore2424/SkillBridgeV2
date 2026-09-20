package com.skillbridge.shared.messaging;

import com.skillbridge.shared.messaging.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The application's vocabulary for AI events. It does not talk to RabbitMQ.
 *
 * <p>Each method records an event in the caller's transaction through
 * {@link OutboxWriter}; {@code OutboxRelay} delivers it. Every caller is a
 * {@code @Transactional} service method, which the writer requires.
 *
 * <h2>What this replaced, and why</h2>
 *
 * <p>This class used to publish from an {@code afterCommit} callback on a
 * background executor, catching every {@code AmqpException} so that a student's
 * save would not fail because the broker was down. The reasoning was right and
 * the consequence was data loss: a broker that was down, slow, or refusing meant
 * the event was simply gone — no retry, no queue, no record it should have
 * existed. The outbox keeps the good half of that trade — a broker outage still
 * does not fail the student's request — and removes the bad half.
 *
 * <h2>Schema version 2</h2>
 *
 * <p>Events go out in an {@link EventEnvelope} with a typed payload, at the version
 * {@link EventType} names. Version 1 was a bare {@code AIEvent} record with the skill
 * id in an untyped {@code metadata} map — the map that once carried a bare number
 * and made every event unreadable. See {@code docs/EVENT_SCHEMA.md}.
 */
@Component
@RequiredArgsConstructor
public class AIEventPublisher {

    private static final String AGGREGATE = "Student";

    private final OutboxWriter outbox;

    /** A student added, changed or removed a skill; the AI service re-runs the gap analysis. */
    public void publishSkillUpdated(Long studentId, Long collegeId, Long skillId) {
        outbox.write(EventType.SKILL_UPDATED, AGGREGATE, studentId, collegeId,
                new EventType.SkillUpdated(studentId, skillId));
    }

    /** A student changed profile fields that feed the AI analysis. */
    public void publishProfileUpdated(Long studentId, Long collegeId) {
        outbox.write(EventType.PROFILE_UPDATED, AGGREGATE, studentId, collegeId,
                new EventType.ProfileUpdated(studentId));
    }
}
