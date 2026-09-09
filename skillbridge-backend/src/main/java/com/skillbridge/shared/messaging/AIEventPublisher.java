package com.skillbridge.shared.messaging;

import com.skillbridge.common.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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
 *
 * <p><b>Events are sent after the calling transaction commits, on another
 * thread.</b> See {@link #send} for why all three of those words matter.
 */
@Component
@Slf4j
public class AIEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final Executor executor;

    public AIEventPublisher(RabbitTemplate rabbitTemplate,
                            @Qualifier("aiEventExecutor") Executor executor) {
        this.rabbitTemplate = rabbitTemplate;
        this.executor = executor;
    }

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
     * Defers the publish until the calling transaction has committed, and runs
     * it on another thread.
     *
     * <p>Every caller of this class is a {@code @Transactional} service method,
     * so publishing inline had three problems, and the comment at one call site
     * ("This runs AFTER the transaction commits so the AI service reads fresh
     * data") described behaviour the code did not have:
     *
     * <ol>
     *   <li><b>The event could describe a change that never happened.</b> The
     *       publish went out mid-transaction; if the transaction then rolled
     *       back, the AI service had already been told about a skill the
     *       database does not contain.</li>
     *   <li><b>The AI service could read stale data.</b> It reacts by querying
     *       the database. Sent before commit, that read races the commit and can
     *       observe the pre-update row — the exact failure the call-site comment
     *       claimed was impossible.</li>
     *   <li><b>It held a database connection across a network call.</b> A
     *       connection is checked out for the life of the transaction; an AMQP
     *       round trip inside it adds the broker's latency to the time the
     *       connection is unavailable to anyone else. That is the most common
     *       cause of pool exhaustion, and this pool has 5 connections against a
     *       server whose ceiling is 60 shared with two other services.</li>
     * </ol>
     *
     * <p>{@code afterCommit} alone fixes (1) and (2) but not (3): Spring runs
     * that callback in {@code triggerAfterCommit}, which is before
     * {@code cleanupAfterCompletion} returns the connection to the pool. So the
     * publish is also handed to {@code aiEventExecutor}, which takes it off the
     * committing thread entirely.
     *
     * <p>With no transaction active the event is sent inline, so callers
     * outside a transaction still work.
     *
     * <p>Errors are logged and swallowed, never rethrown — a failure to publish
     * an AI event must not fail the student's request. The student saved their
     * skill; that is the critical operation. AI analysis is best-effort.
     */
    private void send(AIEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(event);
                }
            });
        } else {
            dispatch(event);
        }
    }

    /** Hands the publish to the executor, falling back to inline on rejection. */
    private void dispatch(AIEvent event) {
        try {
            executor.execute(() -> doSend(event));
        } catch (RejectedExecutionException e) {   // TaskRejectedException extends this
            log.error("[AIEventPublisher] Executor rejected event, dropping it. Event: {}, Error: {}",
                    event, e.getMessage());
        }
    }

    private void doSend(AIEvent event) {
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
