package com.skillbridge.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A database connection must not be held across a network call.
 *
 * <p>A {@code @Transactional} method checks out a connection for its whole
 * duration. Put an HTTP call, a broker publish or an SMTP send inside it and the
 * connection is unavailable for the network round trip as well as the query —
 * which is the usual cause of pool exhaustion, and looks like "the database is
 * slow" rather than "we are holding connections".
 *
 * <p>This has already happened here. {@code StudentService.updateStudentProfile},
 * {@code addSkill} and {@code updateSkillProficiency} are all
 * {@code @Transactional} and all published to RabbitMQ before returning. A
 * comment at one of them even claimed the publish ran after commit; it did not.
 * Fixed 2026-09-09 by deferring the send in {@link
 * com.skillbridge.shared.messaging.AIEventPublisher}.
 *
 * <p>The rule walks the call graph rather than checking direct calls only,
 * because the real defect was two hops deep — {@code StudentService} never
 * mentioned {@code RabbitTemplate}, it called {@code AIEventPublisher}. A rule
 * that only looked at direct calls would have passed against the broken code,
 * which is the kind of guard this codebase has been bitten by before.
 *
 * <p>Bytecode only: no Spring context and no database, so it runs on every build
 * rather than only when {@code DATABASE_URL} is set.
 */
class ConnectionHoldingRulesTest {

    private static final String BASE = "com.skillbridge";

    /**
     * Owners whose methods perform network I/O. Matched on fully-qualified name
     * so a type that is not on the classpath costs nothing rather than failing
     * to compile.
     */
    private static final List<String> NETWORK_CLIENTS = List.of(
            "org.springframework.amqp.core.AmqpTemplate",
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.mail.MailSender",
            "org.springframework.mail.javamail.JavaMailSender",
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.client.RestClient",
            "org.springframework.web.reactive.function.client.WebClient",
            "java.net.http.HttpClient",
            "java.net.URL",
            "java.net.Socket");

    /**
     * Classes the walk may stop at without reporting. EMPTY, and it should stay so.
     *
     * <p>Until 2026-09-14 this held {@code AIEventPublisher}, which reached
     * {@code RabbitTemplate} from an afterCommit callback on a background
     * executor, and was exempt only because a test proved the deferral. The
     * transactional outbox removed the need: the publisher now INSERTs a row and
     * touches no broker, and the only class that publishes, {@code OutboxRelay},
     * is not {@code @Transactional}. So the rule runs with no exceptions at all.
     *
     * <p>Adding a name here turns the rule off for everything reachable through
     * it. Do not do that without a test proving the connection really is
     * released first.
     */
    private static final Set<String> DEFERRED_BOUNDARIES = Set.of();

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("no @Transactional method reaches a network call, however many hops away")
    void transactionalMethodsDoNotHoldConnectionsAcrossNetworkCalls() {
        List<String> violations = new ArrayList<>();

        for (JavaMethod method : production.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(ConnectionHoldingRulesTest::isTransactional)
                .toList()) {
            findNetworkCall(method).ifPresent(path -> violations.add(
                    method.getFullName() + "\n        reaches -> " + path));
        }

        assertThat(violations)
                .withFailMessage("""
                        A @Transactional method reaches a network call, so a database \
                        connection is held for the round trip:

                        %s

                        Move the call outside the transaction. For broker publishes, \
                        write to the outbox with OutboxWriter inside the transaction and \
                        let OutboxRelay publish it, which it does holding no connection.""",
                        String.join("\n\n", violations))
                .isEmpty();
    }

    /** Breadth-first walk of the call graph from {@code start}. */
    private static java.util.Optional<String> findNetworkCall(JavaMethod start) {
        Deque<List<JavaMethod>> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(List.of(start));
        seen.add(start.getFullName());

        while (!queue.isEmpty()) {
            List<JavaMethod> path = queue.poll();
            JavaMethod current = path.get(path.size() - 1);

            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getFullName();

                if (NETWORK_CLIENTS.contains(owner)) {
                    return java.util.Optional.of(describe(path, call));
                }
                // Only walk into our own code, and never past an audited boundary.
                if (!owner.startsWith(BASE) || DEFERRED_BOUNDARIES.contains(owner)) {
                    continue;
                }
                call.getTarget().resolveMember().ifPresent(next -> {
                    if (seen.add(next.getFullName())) {
                        List<JavaMethod> extended = new ArrayList<>(path);
                        extended.add(next);
                        queue.add(extended);
                    }
                });
            }
        }
        return java.util.Optional.empty();
    }

    private static String describe(List<JavaMethod> path, JavaMethodCall call) {
        List<String> hops = new ArrayList<>(path.stream().skip(1).map(JavaMethod::getFullName).toList());
        hops.add(call.getTarget().getFullName());
        return String.join("\n        -> ", hops);
    }

    private static boolean isTransactional(JavaMethod method) {
        return method.isAnnotatedWith(Transactional.class)
                || method.isAnnotatedWith(jakarta.transaction.Transactional.class)
                || method.getOwner().isAnnotatedWith(Transactional.class)
                || method.getOwner().isAnnotatedWith(jakarta.transaction.Transactional.class);
    }
}
