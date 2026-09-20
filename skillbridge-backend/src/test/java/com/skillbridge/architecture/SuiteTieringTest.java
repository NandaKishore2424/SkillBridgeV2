package com.skillbridge.architecture;

import com.skillbridge.testsupport.IntegrationTest;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The suite must be honestly tiered, and no test may decide to skip itself.
 *
 * <h2>The failure this exists to prevent</h2>
 *
 * <p>Eighteen classes carried an {@code EnabledIfEnvironmentVariable} on
 * {@code DATABASE_URL}. It reads as caution. What it produced, on every CI push
 * from the day the workflow was written, was this:
 *
 * <pre>
 * [WARNING] Tests run: 169, Failures: 0, Errors: 0, Skipped: 64
 * [INFO] BUILD SUCCESS
 * [INFO] Total time:  6.352 s
 * </pre>
 *
 * <p>Sixty-four of 169 tests — 38% of the suite, including the tenant filter,
 * token revocation, idempotency and the query-count budget — never ran there.
 * The build was green. The number 169 was reported. Both were true and together
 * they were misleading, which is worse than a red build: a red build gets fixed.
 *
 * <p>The lesson generalises past this codebase. <b>A test that decides for
 * itself whether to run converts a missing dependency into a passing build.</b>
 * Which tiers run is a property of the build — {@code mvn test} for the fast
 * tier, {@code mvn verify} for the integration tier — so the build owns that
 * decision and a missing database produces a failure, which is what a missing
 * database should produce. A CI job that cannot reach one is then absent from
 * the run, which a human notices, rather than green having tested nothing,
 * which nobody does.
 *
 * <h2>The three rules</h2>
 *
 * <ol>
 *   <li>No test self-disables — no environment or system-property condition.</li>
 *   <li>Every {@code @SpringBootTest} is tagged {@link IntegrationTest}, so
 *       Surefire's {@code excludedGroups} actually excludes it. An untagged one
 *       would run in the fast tier, try to reach Supabase, and either blow the
 *       30-second budget or fail the build on a laptop with no credentials.</li>
 *   <li>Every {@link IntegrationTest} really is a {@code @SpringBootTest}. A tag
 *       on a test that needs no database quietly removes it from the fast tier,
 *       which is the same hole wearing the opposite disguise.</li>
 * </ol>
 *
 * <p>Bytecode only: no Spring context and no database, so this runs in the fast
 * tier — the tier it polices.
 */
class SuiteTieringTest {

    private static final String BASE = "com.skillbridge";

    /**
     * Classes allowed to self-disable. Empty, and adding one needs a reason
     * written here that says what makes the skip better than a failure.
     */
    private static final Set<String> MAY_SELF_DISABLE = Set.of();

    /**
     * Classes allowed to carry {@code @IntegrationTest} without
     * {@code @SpringBootTest}. Empty for the same reason.
     */
    private static final Set<String> TAGGED_WITHOUT_CONTEXT = Set.of();

    private static JavaClasses tests;

    @BeforeAll
    static void importTestClasses() {
        // No DO_NOT_INCLUDE_TESTS here -- the test classes are the subject.
        tests = new ClassFileImporter().importPackages(BASE);
    }

    @Test
    @DisplayName("no test class disables itself on a missing environment variable")
    void noTestSelfDisables() {
        List<String> violations = new ArrayList<>();

        for (JavaClass c : testClasses()) {
            if (MAY_SELF_DISABLE.contains(c.getFullName())) {
                continue;
            }
            if (c.isAnnotatedWith(EnabledIfEnvironmentVariable.class)
                    || c.isAnnotatedWith(EnabledIfSystemProperty.class)) {
                violations.add(c.getName()
                        + " gates itself on the environment. A missing dependency must fail "
                        + "the build, not silently pass it. Tag it @IntegrationTest and let "
                        + "`mvn verify` decide.");
            }
        }

        assertThat(violations)
                .as("a skipped test reports as a passing test; the build must own which tiers run")
                .isEmpty();
    }

    @Test
    @DisplayName("every test that needs a database is tagged @IntegrationTest")
    void databaseBackedTestsAreTaggedIntegration() {
        List<String> violations = new ArrayList<>();

        for (JavaClass c : testClasses()) {
            if (needsDatabase(c) && !c.isAnnotatedWith(IntegrationTest.class)) {
                violations.add(c.getName()
                        + " loads a context with a datasource but is not tagged @IntegrationTest, "
                        + "so it would run in the fast tier and reach for a database that is not "
                        + "there.");
            }
        }

        assertThat(violations)
                .as("Surefire excludes the `integration` group; an untagged context test escapes that")
                .isEmpty();
    }

    @Test
    @DisplayName("every @IntegrationTest really needs a database")
    void integrationTaggedTestsStartAContext() {
        List<String> violations = new ArrayList<>();

        for (JavaClass c : testClasses()) {
            if (TAGGED_WITHOUT_CONTEXT.contains(c.getFullName())) {
                continue;
            }
            if (c.isAnnotatedWith(IntegrationTest.class) && !needsDatabase(c)) {
                violations.add(c.getName()
                        + " is tagged @IntegrationTest but loads no context with a datasource. "
                        + "The tag removes it from the fast tier for nothing.");
            }
        }

        assertThat(violations)
                .as("the tag must mean `needs a database`, or it becomes a way to hide a test")
                .isEmpty();
    }

    /**
     * Whether this class loads a context that will want a datasource.
     *
     * <p>{@code @WebMvcTest} is deliberately absent: it excludes the datasource
     * auto-configuration, so a controller slice belongs in the fast tier and the
     * lazy container initializer never starts anything for it. Adding a slice
     * annotation that *does* touch the database ({@code @JdbcTest},
     * {@code @DataR2dbcTest}) means adding it here, or it silently runs in the
     * fast tier.
     */
    private static boolean needsDatabase(JavaClass c) {
        return c.isAnnotatedWith(SpringBootTest.class)
                || c.isAnnotatedWith(DataJpaTest.class)
                || startsItsOwnContainer(c);
    }

    /**
     * A test that holds a {@link PostgreSQLContainer} needs a database as surely
     * as one that loads a Spring context around a datasource — it just brings
     * its own. {@code DemoSeedTest} does, because the file it runs opens with
     * {@code DELETE FROM colleges} and cannot be pointed at the container the
     * rest of the tier shares.
     *
     * <p>Recognising that here, rather than listing the class in
     * {@link #TAGGED_WITHOUT_CONTEXT}, keeps the exemption set empty and makes
     * the rule say what it means.
     */
    private static boolean startsItsOwnContainer(JavaClass c) {
        return c.getFields().stream()
                .anyMatch(f -> f.getRawType().isAssignableTo(PostgreSQLContainer.class));
    }

    /** Test classes only: anything whose simple name ends in Test. */
    private static List<JavaClass> testClasses() {
        return tests.stream()
                .filter(c -> c.getSimpleName().endsWith("Test") || c.getSimpleName().endsWith("Tests"))
                .toList();
    }
}
