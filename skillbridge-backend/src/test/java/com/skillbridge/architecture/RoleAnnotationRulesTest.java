package com.skillbridge.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @PreAuthorize} appears only on the named role annotations.
 *
 * <p>There were 118 of these expressions in twelve spellings, four of which
 * were the same set of roles written two ways. Each is a string the compiler
 * does not check: {@code hasRole('COLLEGE-ADMIN')} or
 * {@code hasAnyRole('COLLEGE_ADMIN','SYSTEMADMIN')} compiles, runs, and admits
 * fewer or more people than intended. Naming them
 * ({@code com.skillbridge.common.security}) makes the set finite and greppable,
 * and {@code EndpointRolesTest} prints who may call what.
 */
class RoleAnnotationRulesTest {

    private static final String ANNOTATIONS = "com.skillbridge.common.security.";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.skillbridge");
    }

    @Test
    @DisplayName("no class outside com.skillbridge.common.security writes a @PreAuthorize expression")
    void onlyNamedRoleAnnotations() {
        List<String> violations = new ArrayList<>();
        production.stream()
                .filter(c -> !c.getName().startsWith(ANNOTATIONS))
                .forEach(c -> {
                    if (c.isAnnotatedWith(PreAuthorize.class)) {
                        violations.add(c.getName());
                    }
                    c.getMethods().stream()
                            .filter(m -> m.isAnnotatedWith(PreAuthorize.class))
                            .forEach(m -> violations.add(m.getFullName()));
                });

        assertThat(violations)
                .withFailMessage("""
                        A raw @PreAuthorize expression:

                        %s

                        Use one of the named annotations in com.skillbridge.common.security, \
                        or add one there if the set of roles is genuinely new.""",
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("every named role annotation carries a @PreAuthorize")
    void theNamesActuallyGuard() {
        List<String> named = production.stream()
                .filter(c -> c.getName().startsWith(ANNOTATIONS) && c.isAnnotation())
                .map(c -> c.getName())
                .toList();
        assertThat(named).as("the named annotations must exist").hasSizeGreaterThanOrEqualTo(8);

        List<String> toothless = production.stream()
                .filter(c -> c.getName().startsWith(ANNOTATIONS) && c.isAnnotation())
                .filter(c -> !c.isAnnotatedWith(PreAuthorize.class))
                .map(c -> c.getName())
                .toList();

        assertThat(toothless)
                .withFailMessage("""
                        These name a role and enforce nothing, so every endpoint using them \
                        admits anyone signed in:

                        %s

                        (This is not hypothetical: on 2026-09-20 a bulk rewrite replaced the \
                        @PreAuthorize inside these very files with the annotation's own name, \
                        and only the controller slice tests noticed.)""", String.join("\n", toothless))
                .isEmpty();
    }
}
