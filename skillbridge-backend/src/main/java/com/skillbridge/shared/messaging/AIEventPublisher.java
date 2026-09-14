package com.skillbridge.shared.messaging;

import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.shared.messaging.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The application's vocabulary for AI events. It no longer talks to RabbitMQ.
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
 * existed. And an executor that rejected the task dropped it too.
 *
 * <p>The outbox keeps the good half of that trade — a broker outage still does
 * not fail the student's request, because the broker is not on this path — and
 * removes the bad half: the event is a committed row, and it stays one until the
 * broker confirms it.
 *
 * <p>The payload is still the {@link AIEvent} record, so the wire format is
 * unchanged and {@code contracts/ai-events/v1} still describes it.
 */
@Component
@RequiredArgsConstructor
public class AIEventPublisher {

    private static final String AGGREGATE = "Student";

    private final OutboxWriter outbox;

    /** A student added or changed a skill; the AI service re-runs the gap analysis. */
    public void publishSkillUpdated(Long studentId, Long collegeId, Long skillId) {
        // metadata is an object, never the bare Long it once was: the consumer
        // calls .get() on it. See AiEventContractTest.
        outbox.write(AGGREGATE, studentId, "SKILL_UPDATED", RabbitMQConfig.SKILL_UPDATED_KEY,
                new AIEvent("SKILL_UPDATED", studentId, collegeId, Map.of("skillId", skillId)));
    }

    /** A student changed profile fields that feed the AI analysis. */
    public void publishProfileUpdated(Long studentId, Long collegeId) {
        outbox.write(AGGREGATE, studentId, "PROFILE_UPDATED", RabbitMQConfig.PROFILE_UPDATED_KEY,
                new AIEvent("PROFILE_UPDATED", studentId, collegeId, null));
    }
}
