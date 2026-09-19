package com.skillbridge.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No transaction reaches a {@code REQUIRES_NEW} one, however many calls away.
 *
 * <p>{@code REQUIRES_NEW} inside a transaction takes a second connection while
 * the first is held. When as many requests do that at once as the pool has
 * connections, every one holds one and waits for another that will never be
 * free: the pool starves, and all of them fail at the connection timeout. On
 * 2026-09-19 this rule's first run found nine such methods -- four in
 * {@code AuthService}, five in {@code EnrollmentManagementService} -- all
 * reaching the audit writer; {@code ConcurrentLoginPoolTest} then failed ten
 * logins out of ten on a pool of five. The audit writer now has its own pool.
 *
 * <p>{@code REQUIRES_NEW} itself is not banned. Called from code that holds no
 * transaction (a filter, a scheduled job, the CSV importer) it opens the only
 * connection in play, which is fine.
 *
 * <p>Walks the call graph like {@code ConnectionHoldingRulesTest}, and with the
 * same limit: it cannot follow a call through an interface to an
 * implementation.
 */
class NestedTransactionRulesTest {

    private static final String BASE = "com.skillbridge";
    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("no @Transactional method reaches a REQUIRES_NEW method")
    void noNestedTransactions() {
        List<String> violations = new ArrayList<>();
        for (JavaMethod method : production.stream().flatMap(c -> c.getMethods().stream())
                .filter(NestedTransactionRulesTest::isTransactional).toList()) {
            findRequiresNew(method).ifPresent(path -> violations.add(method.getFullName() + "\n    -> " + path));
        }
        assertThat(violations).withFailMessage("""
                A @Transactional method reaches a REQUIRES_NEW method, so it holds two \
                connections at once; enough of these together starve the pool:

                %s

                Do the REQUIRES_NEW work before or after the transaction, or give it \
                its own pool as AuditLogWriter does.""", String.join("\n\n", violations)).isEmpty();
    }

    private static Optional<String> findRequiresNew(JavaMethod start) {
        Deque<List<JavaMethod>> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(List.of(start));
        seen.add(start.getFullName());
        while (!queue.isEmpty()) {
            List<JavaMethod> path = queue.poll();
            for (JavaMethodCall call : path.get(path.size() - 1).getMethodCallsFromSelf()) {
                if (!call.getTargetOwner().getFullName().startsWith(BASE)) {
                    continue;
                }
                Optional<JavaMethod> next = call.getTarget().resolveMember();
                if (next.isEmpty() || !seen.add(next.get().getFullName())) {
                    continue;
                }
                List<JavaMethod> extended = new ArrayList<>(path);
                extended.add(next.get());
                if (requiresNew(next.get())) {
                    return Optional.of(String.join("\n    -> ", extended.stream().skip(1).map(JavaMethod::getFullName).toList()));
                }
                queue.add(extended);
            }
        }
        return Optional.empty();
    }

    private static boolean requiresNew(JavaMethod m) {
        return m.tryGetAnnotationOfType(Transactional.class).map(t -> t.propagation() == Propagation.REQUIRES_NEW).orElse(false);
    }

    private static boolean isTransactional(JavaMethod m) {
        return m.isAnnotatedWith(Transactional.class) || m.getOwner().isAnnotatedWith(Transactional.class);
    }
}
