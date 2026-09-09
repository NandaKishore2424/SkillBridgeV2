package com.skillbridge.architecture;

import com.skillbridge.common.idempotency.Idempotent;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two ways to put {@link Idempotent} somewhere it cannot work.
 *
 * <p>Both fail at runtime rather than at startup, and both fail quietly — the
 * annotation simply stops protecting anything — which is the worst way for a
 * safety mechanism to be wrong.
 */
class IdempotencyRulesTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.skillbridge");
    }

    private static List<JavaMethod> annotated() {
        return production.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(Idempotent.class))
                .toList();
    }

    @Test
    @DisplayName("@Idempotent is never on a multipart handler")
    void notOnMultipartHandlers() {
        List<String> violations = new ArrayList<>();
        for (JavaMethod method : annotated()) {
            boolean multipart = method.getRawParameterTypes().stream()
                    .anyMatch(p -> p.isAssignableTo(MultipartFile.class)
                                || p.getName().equals(MultipartFile[].class.getName()));
            if (multipart) {
                violations.add(method.getFullName());
            }
        }
        assertThat(violations)
                .withFailMessage("""
                        @Idempotent on a multipart handler:

                        %s

                        IdempotencyKeyFilter skips multipart requests -- the servlet \
                        container parses those from its own stream, so the body cannot \
                        be buffered underneath it. The annotation would be silently \
                        inert: every request would be treated as a fresh one.""",
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("@Idempotent is never on a GET handler")
    void notOnReads() {
        List<String> violations = annotated().stream()
                .filter(m -> m.isAnnotatedWith(GetMapping.class))
                .map(JavaMethod::getFullName)
                .toList();
        assertThat(violations)
                .withFailMessage("""
                        @Idempotent on a GET handler:

                        %s

                        A GET has no body to hash and is already meant to be safe to \
                        repeat. Annotating one only adds a required header to a read.""",
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("something is actually annotated")
    void theMechanismIsInUse() {
        // Both rules above pass vacuously if nothing carries the annotation,
        // which is exactly the state this task started in: the table existed
        // and nothing wrote to it.
        assertThat(annotated())
                .as("no handler is @Idempotent -- the mechanism is dead code again")
                .isNotEmpty();
    }
}
