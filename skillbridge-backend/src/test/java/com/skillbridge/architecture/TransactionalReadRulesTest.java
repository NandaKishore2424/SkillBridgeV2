package com.skillbridge.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service method that touches a repository must own a transaction.
 *
 * <p>Two things go wrong without one, and neither announces itself.
 *
 * <p><b>The tenant filter silently does not apply.</b>
 * {@link com.skillbridge.common.tenant.TenantFilterAspect} enables Hibernate's
 * {@code collegeFilter} on the session bound to the current transaction, and it
 * only advises {@code @Transactional} methods — so a method without one gets no
 * filter at all. That is a safety net rather than the primary control (by-id
 * lookups go through {@code TenantGuard} explicitly), but a safety net that is
 * absent on half the read paths is not one.
 *
 * <p><b>Every repository call becomes its own transaction.</b> Spring Data makes
 * each {@code findAll} or {@code save} transactional on its own, so the code
 * works — it just pays a {@code BEGIN}/{@code COMMIT} round trip per call to a
 * database in another region. Measured on {@code GET /admin/students}: 5.00
 * connection checkouts per request before annotating the read methods, 2.00
 * after. It also means a method reading several times sees several snapshots,
 * and a read-check-then-write has its check in a different transaction from its
 * write — which is how {@code SyllabusService.createModule} could pass
 * {@code existsByBatchIdAndDisplayOrder} and still race another insert.
 *
 * <p>Bytecode only: no Spring context and no database, so it runs on every build
 * rather than only when {@code DATABASE_URL} is set.
 */
class TransactionalReadRulesTest {

    private static final String BASE = "com.skillbridge";

    /**
     * Methods that reach a repository but must not hold a transaction, each for
     * a reason that has been checked. Anything added here needs one written down.
     */
    private static final Set<String> ALLOWED = Set.of(
            // Kicks off an @Async job and returns. Holding a transaction across
            // the handoff would keep a connection for work on another thread.
            "com.skillbridge.bulkupload.service.BulkUploadService.startStudentUpload",
            "com.skillbridge.bulkupload.service.BulkUploadService.startTrainerUpload",
            // The async job itself. It holds no transaction at all: each
            // repository call commits on its own, so a row that fails halfway
            // keeps what it already wrote (an orphan user, measured 2026-09-17).
            // Phase 2 rebuilds the import with one transaction per row.
            "com.skillbridge.bulkupload.service.BulkUploadJobService.processStudentUploadAsync",
            "com.skillbridge.bulkupload.service.BulkUploadJobService.processTrainerUploadAsync",
            // Deliberately outside the caller's transaction: an audit record of a
            // failed operation must survive that operation's rollback.
            "com.skillbridge.common.audit.AuditLogService.record",
            "com.skillbridge.common.audit.AuditLogService.recordFor",
            "com.skillbridge.common.audit.AuditLogService.recordAnonymous");

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    @DisplayName("every public @Service method that reaches a repository is @Transactional")
    void serviceMethodsTouchingRepositoriesAreTransactional() {
        List<String> violations = new ArrayList<>();

        for (JavaClass service : production.stream()
                .filter(c -> c.isAnnotatedWith(Service.class))
                .toList()) {
            boolean classLevel = isTransactional(service);
            for (JavaMethod method : service.getMethods()) {
                if (!method.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.PUBLIC)) {
                    continue;
                }
                if (classLevel || isTransactional(method)) {
                    continue;
                }
                String id = service.getName() + "." + method.getName();
                if (ALLOWED.contains(id)) {
                    continue;
                }
                repositoryCall(method).ifPresent(target -> violations.add(id + "  ->  " + target));
            }
        }

        assertThat(violations)
                .withFailMessage("""
                        These public service methods reach a repository without a \
                        transaction, so collegeFilter is never enabled for them and \
                        every repository call pays its own BEGIN/COMMIT:

                        %s

                        Annotate reads @Transactional(readOnly = true) and writes \
                        @Transactional. If a method genuinely must not hold one, add \
                        it to ALLOWED with the reason.""",
                        String.join("\n", violations))
                .isEmpty();
    }

    /** First repository method this one reaches, directly or through our own code. */
    private static java.util.Optional<String> repositoryCall(JavaMethod start) {
        java.util.Deque<JavaMethod> queue = new java.util.ArrayDeque<>();
        Set<String> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start.getFullName());

        while (!queue.isEmpty()) {
            JavaMethod current = queue.poll();
            for (JavaMethodCall call : current.getMethodCallsFromSelf()) {
                JavaClass owner = call.getTargetOwner();
                if (isRepository(owner)) {
                    return java.util.Optional.of(owner.getSimpleName() + "." + call.getName());
                }
                if (!owner.getName().startsWith(BASE)) {
                    continue;
                }
                call.getTarget().resolveMember().ifPresent(next -> {
                    if (seen.add(next.getFullName())) {
                        queue.add(next);
                    }
                });
            }
        }
        return java.util.Optional.empty();
    }

    private static boolean isRepository(JavaClass type) {
        return type.getAllRawInterfaces().stream()
                .anyMatch(i -> i.getName().startsWith("org.springframework.data.repository")
                            || i.getName().startsWith("org.springframework.data.jpa.repository"));
    }

    private static boolean isTransactional(JavaClass type) {
        return type.isAnnotatedWith(Transactional.class)
                || type.isAnnotatedWith(jakarta.transaction.Transactional.class);
    }

    private static boolean isTransactional(JavaMethod method) {
        return method.isAnnotatedWith(Transactional.class)
                || method.isAnnotatedWith(jakarta.transaction.Transactional.class);
    }
}
