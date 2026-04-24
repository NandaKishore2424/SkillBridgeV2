package com.skillbridge.shared.messaging;

import com.skillbridge.common.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * AIEventPublisher — the ONLY class in the entire application allowed to send
 * messages to the RabbitMQ AI queue.
 *
 * Design Decision: By isolating all RabbitMQ publishing logic here, we
 * achieve two things:
 *   1. If we ever swap RabbitMQ for Kafka, we change ONE file, nothing else.
 *   2. All other services (StudentService, BatchService) stay clean and
 *      testable without any messaging infrastructure.
 *
 * Usage: Inject this component anywhere and call a descriptive publish method.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AIEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Fires a SKILL_UPDATED event when a student adds or updates a skill.
     * The AI service will use this to re-run the skill gap analysis asynchronously.
     *
     * @param studentId  the student whose skill was changed
     * @param collegeId  the tenant this student belongs to
     * @param skillId    which specific skill was added/updated
     */
    public void publishSkillUpdated(Long studentId, Long collegeId, Long skillId) {
        AIEvent event = new AIEvent("SKILL_UPDATED", studentId, collegeId, skillId);
        send(event);
    }

    /**
     * Fires a PROFILE_UPDATED event when a student updates their bio,
     * GitHub URL, or other profile fields that affect their AI analysis.
     *
     * @param studentId the student whose profile changed
     * @param collegeId the tenant this student belongs to
     */
    public void publishProfileUpdated(Long studentId, Long collegeId) {
        AIEvent event = new AIEvent("PROFILE_UPDATED", studentId, collegeId, null);
        send(event);
    }

    /**
     * Internal send method with bulletproof error handling.
     *
     * Design Decision: We wrap the send in try-catch and ONLY log the error
     * instead of re-throwing. This is intentional — a failure to publish an
     * AI event should NEVER cause the main student API request to fail.
     * The student saved their skill; that's the critical operation. AI analysis
     * is a "best-effort" background enhancement, not a hard requirement.
     */
    private void send(AIEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.ROUTING_KEY,
                    event
            );
            log.info("[AIEventPublisher] Event sent to RabbitMQ: type={}, studentId={}, collegeId={}",
                    event.eventType(), event.studentId(), event.collegeId());
        } catch (AmqpException e) {
            // Log and swallow — the main business transaction must NOT be rolled back
            // because the AI notification pipeline is temporarily unavailable.
            log.error("[AIEventPublisher] FAILED to send event to RabbitMQ. " +
                    "The student operation succeeded but AI analysis will be skipped. " +
                    "Event: {}, Error: {}", event, e.getMessage());
        }
    }
}
