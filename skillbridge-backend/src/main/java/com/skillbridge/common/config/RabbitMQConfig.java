package com.skillbridge.common.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The broker topology, declared in exactly one place.
 *
 * <pre>
 *  OutboxRelay --ai.skill.updated--&gt; skillbridge.events (topic)
 *                                          | ai.#
 *                                          v
 *                               skillbridge.ai.analysis (quorum) --&gt; AI consumer
 *                                          |                             |
 *                     nack, delivery limit |                             | transient failure:
 *                                          v                             | republish, then ack
 *            skillbridge.dlq &lt;-- skillbridge.dlx (fanout)                 v
 *                 |                        ^                  skillbridge.retry (direct)
 *                 |                        |                     |      |       |
 *                 |    AI consumer: permanent failure, or      .5s    .30s    .5m   (TTL, no consumer)
 *                 |    retries exhausted -- published with       |      |       |
 *                 |    a reason header, then acked            expire --&gt; default exchange
 *                 v                                                  --&gt; skillbridge.ai.analysis
 *   DeadLetterRecorder --&gt; dead_letter_events --&gt; a person: replay (through the outbox) or discard
 * </pre>
 *
 * <p>The Python service only checks the queue exists (a passive declare), so the
 * arguments cannot drift between two declarers; a mismatch there is a
 * {@code PRECONDITION_FAILED} that kills the channel. Both sides are tested
 * against {@code contracts/amqp/topology.json}.
 *
 * <h2>Three departures from the phase document, each found by trying it</h2>
 *
 * <ol>
 *   <li><b>{@code reject-publish}, not {@code reject-publish-dlx}.</b> A quorum
 *       queue accepts {@code reject-publish-dlx} and does not honour it. Measured
 *       on RabbitMQ 3.13.7 with a queue capped at one message: the broker kept the
 *       NEW message and dead-lettered the OLDEST; a classic queue with the same
 *       arguments, as the control, rejected the new one. Under load that
 *       configuration would quietly evict the oldest waiting events. With
 *       {@code reject-publish} an overloaded queue refuses the publish, and the
 *       outbox keeps the event PENDING.</li>
 *   <li><b>The consumer chooses the retry tier.</b> The document sets one static
 *       {@code x-dead-letter-routing-key: retry.5s}, so every nack reaches the 5s
 *       queue and the 30s and 5m tiers are unreachable. The consumer republishes
 *       to the tier its {@code x-retry-attempt} header calls for, then acks.</li>
 *   <li><b>A new queue name.</b> The old {@code ai.analysis.queue} is a classic
 *       queue, and redeclaring an existing queue with different arguments fails
 *       with {@code PRECONDITION_FAILED}. The old queue and
 *       {@code skillbridge-ai-exchange} are no longer declared by anything; on a
 *       broker that has them, drain and delete them by hand.</li>
 * </ol>
 *
 * <p>{@code x-dead-letter-strategy: at-least-once} on every queue that
 * dead-letters: the quorum default is at-most-once, which may drop a message
 * during dead-lettering. It requires {@code reject-publish} overflow, which is
 * the other reason for point 1.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EVENTS_EXCHANGE = "skillbridge.events";
    public static final String RETRY_EXCHANGE = "skillbridge.retry";
    public static final String DEAD_LETTER_EXCHANGE = "skillbridge.dlx";

    public static final String AI_ANALYSIS_QUEUE = "skillbridge.ai.analysis";
    public static final String AI_BINDING_KEY = "ai.#";
    public static final String DEAD_LETTER_QUEUE = "skillbridge.dlq";

    /** Hierarchical keys: a future notification service binds notification.# unaware of these. */
    public static final String SKILL_UPDATED_KEY = "ai.skill.updated";
    public static final String PROFILE_UPDATED_KEY = "ai.profile.updated";

    /**
     * The routing key each event type is published with. A replay looks the key up
     * here rather than trusting the dead letter's own routing key, which by then is
     * often a retry queue's name: a message that went round the tiers comes back
     * through the default exchange, and its original key is not in the message at all.
     */
    public static final Map<String, String> ROUTING_KEYS = Map.of(
            "SKILL_UPDATED", SKILL_UPDATED_KEY,
            "PROFILE_UPDATED", PROFILE_UPDATED_KEY);

    /** How many retries the consumer has scheduled; carried on the message. */
    public static final String RETRY_ATTEMPT_HEADER = "x-retry-attempt";

    /**
     * Set by a consumer that dead-letters a message itself, so the DLQ records why.
     * A message the broker dead-lettered carries {@code x-death} instead.
     */
    public static final String FAILURE_REASON_HEADER = "x-failure-reason";
    /** Epoch milliseconds. */
    public static final String FAILED_AT_HEADER = "x-failed-at";
    public static final String FAILED_QUEUE_HEADER = "x-failed-queue";
    public static final int MAX_FAILURE_REASON_LENGTH = 2000;

    /** Caps broker memory against a runaway producer. Overflow refuses the publish. */
    public static final int AI_QUEUE_MAX_LENGTH = 100_000;

    /**
     * Redeliveries before the broker dead-letters a message itself. The backstop for
     * a message that crashes the consumer mid-processing, so it is never acked,
     * nacked or retried and would otherwise be redelivered for ever.
     */
    public static final int AI_QUEUE_DELIVERY_LIMIT = 10;

    public record RetryTier(String queue, int ttlMs) {
    }

    public static final List<RetryTier> RETRY_TIERS = List.of(
            new RetryTier(AI_ANALYSIS_QUEUE + ".retry.5s", 5_000),
            new RetryTier(AI_ANALYSIS_QUEUE + ".retry.30s", 30_000),
            new RetryTier(AI_ANALYSIS_QUEUE + ".retry.5m", 300_000));

    /** Declared by RabbitAdmin on the first connection. */
    @Bean
    public Declarables messagingTopology() {
        return topology();
    }

    /**
     * The whole topology as values. Static so the broker tests declare exactly what
     * production declares rather than a copy that can drift from it.
     */
    public static Declarables topology() {
        List<Declarable> declarables = new ArrayList<>();

        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        DirectExchange retry = new DirectExchange(RETRY_EXCHANGE, true, false);
        FanoutExchange deadLetter = new FanoutExchange(DEAD_LETTER_EXCHANGE, true, false);
        declarables.add(events);
        declarables.add(retry);
        declarables.add(deadLetter);

        Queue aiAnalysis = QueueBuilder.durable(AI_ANALYSIS_QUEUE)
                .withArgument("x-queue-type", "quorum")
                .withArgument("x-max-length", AI_QUEUE_MAX_LENGTH)
                .withArgument("x-overflow", "reject-publish")
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .withArgument("x-delivery-limit", AI_QUEUE_DELIVERY_LIMIT)
                .build();
        declarables.add(aiAnalysis);
        declarables.add(BindingBuilder.bind(aiAnalysis).to(events).with(AI_BINDING_KEY));

        for (RetryTier tier : RETRY_TIERS) {
            // A queue with a TTL and no consumer. It holds a message for ttlMs, then
            // dead-letters it through the default exchange straight back into the
            // analysis queue: delayed redelivery with no plugin and no scheduler.
            Queue delay = QueueBuilder.durable(tier.queue())
                    .withArgument("x-queue-type", "quorum")
                    .withArgument("x-message-ttl", tier.ttlMs())
                    .withArgument("x-dead-letter-exchange", "")
                    .withArgument("x-dead-letter-routing-key", AI_ANALYSIS_QUEUE)
                    .withArgument("x-dead-letter-strategy", "at-least-once")
                    .withArgument("x-overflow", "reject-publish")
                    .build();
            declarables.add(delay);
            declarables.add(BindingBuilder.bind(delay).to(retry).with(tier.queue()));
        }

        // No TTL. A message stays here until a person decides what to do with it.
        Queue dlq = QueueBuilder.durable(DEAD_LETTER_QUEUE)
                .withArgument("x-queue-type", "quorum")
                .build();
        declarables.add(dlq);
        declarables.add(BindingBuilder.bind(dlq).to(deadLetter));

        return new Declarables(declarables);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
}
