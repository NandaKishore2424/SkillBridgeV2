package com.skillbridge.architecture;

import com.skillbridge.student.entity.Student;
import com.skillbridge.student.entity.StudentSkill;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * No JPA entity defines {@code equals}, {@code hashCode} or {@code toString}.
 *
 * <p>Until 2026-09-19, fifteen entities carried Lombok's {@code @Data}, which
 * generates all three over <i>every</i> field, associations included. That goes
 * wrong in three ways, none of which fails at startup:
 *
 * <ul>
 *   <li><b>A bidirectional association recurses.</b> {@code Student.skills}
 *       holds {@code StudentSkill}s that point back at the student, so
 *       {@code student.hashCode()} and {@code student.toString()} ended in a
 *       {@code StackOverflowError}.</li>
 *   <li><b>It touches lazy associations.</b> Hashing an entity loads its
 *       proxies, one query each, or throws {@code LazyInitializationException}
 *       outside a transaction.</li>
 *   <li><b>{@code toString} prints secrets.</b> {@code User.passwordHash} and
 *       {@code RefreshToken.tokenHash} went into any log line that printed the
 *       entity.</li>
 * </ul>
 *
 * <p>Why not id-only equality instead? Two unsaved instances both have
 * {@code id == null} and would compare equal, so a set of new entities
 * collapses to one. Object identity is what Hibernate already guarantees inside
 * a persistence context (one row, one instance), and nothing here compares
 * entities across contexts: collection edits go by id
 * ({@code removeIf(t -> t.getId().equals(id))}). Should that change, write the
 * methods by hand, with a test, and exempt the class here with the reason.
 */
class EntityIdentityRulesTest {

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.skillbridge");
    }

    @Test
    @DisplayName("no @Entity declares equals, hashCode or toString")
    void entitiesKeepObjectIdentity() {
        List<JavaClass> entities = production.stream()
                .filter(c -> c.isAnnotatedWith(Entity.class))
                .toList();
        // A rule that matches nothing passes whatever the code says.
        assertThat(entities).as("the importer must find the entities").hasSizeGreaterThan(20);

        List<String> violations = new ArrayList<>();
        for (JavaClass entity : entities) {
            entity.tryGetMethod("equals", Object.class).ifPresent(m -> violations.add(m.getFullName()));
            entity.tryGetMethod("hashCode").ifPresent(m -> violations.add(m.getFullName()));
            entity.tryGetMethod("toString").ifPresent(m -> violations.add(m.getFullName()));
        }

        assertThat(violations)
                .withFailMessage("""
                        Entities that define equals, hashCode or toString:

                        %s

                        Usually Lombok's @Data or @EqualsAndHashCode/@ToString. Over \
                        associations they recurse, load lazy proxies, and print \
                        password and token hashes. Use @Getter and @Setter; see this \
                        test's javadoc for why identity equality is enough here.""",
                        String.join("\n", violations))
                .isEmpty();
    }

    @Test
    @DisplayName("a student and its skills, which point at each other, hash and print without recursing")
    void bidirectionalAssociationDoesNotRecurse() {
        // The failure itself, as reproduced before the fix. It needs @Data on BOTH
        // sides to recurse, so alone it misses a half-reintroduction; the rule
        // above catches either side. Checked 2026-09-19: @Data on Student only
        // turns the rule red; on Student and StudentSkill, both tests.
        Student student = new Student();
        StudentSkill link = new StudentSkill();
        link.setStudent(student);
        student.getSkills().add(link);

        assertThatNoException().isThrownBy(() -> {
            student.hashCode();
            student.toString();
            link.hashCode();
            link.toString();
        });
    }
}
