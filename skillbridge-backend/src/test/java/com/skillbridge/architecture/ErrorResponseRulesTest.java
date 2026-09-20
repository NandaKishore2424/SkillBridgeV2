package com.skillbridge.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A controller does not build a failure response itself.
 *
 * <p>Every failure goes through an exception and {@code GlobalExceptionHandler},
 * so every one carries the same body: timestamp, status, error code, message,
 * path. Until 2026-09-19 six endpoints answered {@code notFound().build()} --
 * 404 with an empty body -- and {@code createCompany} answered 400 with a bare
 * string, so a client had to special-case them. {@code CrossTenantAccessTest}
 * catches the 404s it provokes; this catches the ones no test happens to call.
 *
 * <p>Only the failure builders are banned. {@code ok}, {@code created},
 * {@code accepted} and {@code noContent} are how a controller answers.
 */
class ErrorResponseRulesTest {

    /** ResponseEntity factory methods that start a 4xx or 5xx response. */
    private static final Set<String> FORBIDDEN = Set.of("notFound", "badRequest", "unprocessableEntity",
            "internalServerError");

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.skillbridge");
    }

    @Test
    @DisplayName("no controller calls ResponseEntity.notFound, badRequest, unprocessableEntity or internalServerError")
    void controllersThrowInsteadOfBuildingErrors() {
        List<String> controllers = production.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class) || c.isAnnotatedWith(Controller.class))
                .map(c -> c.getName())
                .toList();
        // A rule that matches no class passes whatever the code does.
        assertThat(controllers).as("the importer must find the controllers").hasSizeGreaterThan(15);

        List<String> violations = production.stream()
                .filter(c -> c.isAnnotatedWith(RestController.class) || c.isAnnotatedWith(Controller.class))
                .flatMap(c -> c.getMethods().stream())
                .flatMap(m -> m.getMethodCallsFromSelf().stream())
                .filter(ErrorResponseRulesTest::buildsAnError)
                .map(JavaMethodCall::getDescription)
                .toList();

        assertThat(violations)
                .withFailMessage("""
                        A controller builds a failure response itself:

                        %s

                        Throw instead -- ResourceNotFoundException, BadRequestException, \
                        ForbiddenException -- so the response has the same body as every \
                        other failure.""", String.join("\n", violations))
                .isEmpty();
    }

    private static boolean buildsAnError(JavaMethodCall call) {
        return call.getTargetOwner().isEquivalentTo(ResponseEntity.class)
                && FORBIDDEN.contains(call.getTarget().getName());
    }
}
