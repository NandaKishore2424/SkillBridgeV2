package com.skillbridge.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox is the only road to the broker.
 *
 * <p>The transactional outbox guarantees an event is delivered if and only if its
 * transaction committed — but only for events that go through it. A single
 * service that injects {@code RabbitTemplate} and publishes directly reopens every
 * hole the outbox closed: an event for a rolled-back change, an event lost when
 * the broker is down, a connection held across a network call. And it would look
 * like perfectly ordinary Spring code in review.
 *
 * <p>So: no production class may depend on a publishing template except the one
 * that relays the outbox, and the configuration class that declares the beans.
 * Bytecode only, fast tier.
 */
class MessagingBoundaryRulesTest {

    private static final Set<String> PUBLISHING_TYPES = Set.of(
            "org.springframework.amqp.core.AmqpTemplate",
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.amqp.rabbit.core.RabbitOperations",
            "org.springframework.amqp.rabbit.core.RabbitMessagingTemplate",
            // The raw client channel publishes too (basicPublish), and bypasses everything.
            "com.rabbitmq.client.Channel");

    /** Everything else is a violation. Adding a name here needs a reason written beside it. */
    private static final Set<String> ALLOWED = Set.of(
            // Relays committed outbox rows; publishes holding no database connection.
            "com.skillbridge.shared.messaging.outbox.OutboxRelay",
            // Declares the RabbitTemplate bean; constructs it, never publishes with it.
            "com.skillbridge.common.config.RabbitMQConfig",
            // Takes a Channel only to ack DLQ deliveries by hand, after the insert
            // commits. It never publishes; a replay goes through OutboxWriter.
            "com.skillbridge.shared.messaging.deadletter.DeadLetterRecorder");

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.skillbridge");
    }

    @Test
    @DisplayName("only OutboxRelay, RabbitMQConfig and the DLQ recorder depend on a broker publishing type")
    void onlyTheRelayReachesTheBroker() {
        Set<String> violations = new TreeSet<>();

        for (JavaClass c : production) {
            String owner = outermost(c).getFullName();
            if (ALLOWED.contains(owner)) {
                continue;
            }
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                if (PUBLISHING_TYPES.contains(d.getTargetClass().getFullName())) {
                    violations.add(owner + " -> " + d.getTargetClass().getSimpleName());
                }
            }
        }

        assertThat(violations)
                .withFailMessage("""
                        These classes reach the broker directly, bypassing the outbox:

                        %s

                        Write the event with OutboxWriter inside the business transaction \
                        instead. A direct publish can announce a change that rolls back, \
                        and loses the event if the broker is down.""",
                        String.join("\n", violations))
                .isEmpty();
    }

    /** Lambdas and inner classes are reported against the class that wrote them. */
    private static JavaClass outermost(JavaClass c) {
        JavaClass current = c;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }
}
