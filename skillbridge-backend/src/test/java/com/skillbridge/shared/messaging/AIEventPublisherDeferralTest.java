package com.skillbridge.shared.messaging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AIEventPublisher} must not publish until the calling transaction has
 * committed, and must not publish on the committing thread.
 *
 * <p>This test is what makes the {@code AIEventPublisher} exclusion in {@link
 * com.skillbridge.architecture.ConnectionHoldingRulesTest} honest. That rule
 * stops walking the call graph at this class on the grounds that it defers; if
 * the deferral were removed, the rule would go on passing and every
 * {@code @Transactional} caller would quietly be holding a connection across an
 * AMQP round trip again. These assertions fail instead.
 *
 * <p>Three properties, each of which was untrue before 2026-09-09:
 *
 * <ul>
 *   <li>nothing is sent while the transaction is still open — a rollback used
 *       to leave the AI service told about a change the database never kept;</li>
 *   <li>the send happens after commit, so the AI service's own read of the row
 *       cannot race the commit;</li>
 *   <li>the send runs on the executor, not the committing thread, so the
 *       connection is not held for the publish — {@code afterCommit} alone runs
 *       before Spring returns the connection to the pool.</li>
 * </ul>
 */
class AIEventPublisherDeferralTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("with a transaction active, nothing is published until commit")
    void doesNotPublishBeforeCommit() {
        AIEventPublisher publisher = new AIEventPublisher(rabbitTemplate, Runnable::run);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishSkillUpdated(1L, 1L, 1L);

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());
        assertThat(TransactionSynchronizationManager.getSynchronizations())
                .as("the publish should be registered as a transaction synchronization")
                .hasSize(1);
    }

    @Test
    @DisplayName("the publish happens when the transaction commits")
    void publishesOnAfterCommit() {
        AIEventPublisher publisher = new AIEventPublisher(rabbitTemplate, Runnable::run);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishSkillUpdated(1L, 1L, 1L);
        // Assert the ordering, not just that it eventually happened: publishing
        // inline would also satisfy a bare verify() here, so without this first
        // check the test stays green against the very bug it guards.
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());

        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());
    }

    @Test
    @DisplayName("a rolled back transaction publishes nothing")
    void publishesNothingOnRollback() {
        AIEventPublisher publisher = new AIEventPublisher(rabbitTemplate, Runnable::run);
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishProfileUpdated(1L, 1L);
        // afterCommit is precisely what does not run on a rollback.
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(org.springframework.transaction.support
                        .TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), (Object) any());
    }

    @Test
    @DisplayName("the send runs on the executor, not the committing thread")
    void publishesOffTheCommittingThread() throws Exception {
        AtomicReference<Thread> sendingThread = new AtomicReference<>();
        CountDownLatch sent = new CountDownLatch(1);
        Executor realExecutor = task -> new Thread(() -> {
            task.run();
            sent.countDown();
        }, "test-ai-event").start();

        RabbitTemplate recording = mock(RabbitTemplate.class);
        org.mockito.Mockito.doAnswer(inv -> {
            sendingThread.set(Thread.currentThread());
            return null;
        }).when(recording).convertAndSend(anyString(), anyString(), (Object) any());

        AIEventPublisher publisher = new AIEventPublisher(recording, realExecutor);
        TransactionSynchronizationManager.initSynchronization();
        publisher.publishSkillUpdated(1L, 1L, 1L);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

        assertThat(sent.await(5, TimeUnit.SECONDS)).as("the event should reach the broker").isTrue();
        assertThat(sendingThread.get())
                .as("publishing on the committing thread holds its database connection for the round trip")
                .isNotSameAs(Thread.currentThread());
    }

    @Test
    @DisplayName("with no transaction active the event is sent inline")
    void sendsInlineWithoutATransaction() {
        AtomicInteger dispatches = new AtomicInteger();
        AIEventPublisher publisher = new AIEventPublisher(rabbitTemplate, task -> {
            dispatches.incrementAndGet();
            task.run();
        });

        publisher.publishProfileUpdated(1L, 1L);

        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), (Object) any());
        assertThat(dispatches).hasValue(1);
    }
}
