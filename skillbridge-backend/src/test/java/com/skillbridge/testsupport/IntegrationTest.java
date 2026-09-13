package com.skillbridge.testsupport;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a test that needs a real PostgreSQL database.
 *
 * <p>Put this on every class that also carries {@code @SpringBootTest}. Surefire
 * excludes the {@code integration} group, so these do not run in the fast tier;
 * Failsafe includes it, so they run under {@code mvn verify}.
 *
 * <h2>Why this replaced {@code @EnabledIfEnvironmentVariable}</h2>
 *
 * <p>Every one of these classes used to gate itself, with an
 * {@code EnabledIfEnvironmentVariable} keyed on {@code DATABASE_URL} and a
 * {@code disabledReason} of "needs a PostgreSQL instance; set DATABASE_URL to
 * run".
 *
 * <p>That reads as caution and behaves as a hole. With no {@code DATABASE_URL},
 * {@code mvn test} reported <b>"Tests run: 169, Failures: 0, Errors: 0, Skipped:
 * 64"</b> and <b>BUILD SUCCESS</b> in 6.4 seconds — and that is exactly what CI
 * ran on every push from the day the workflow was written. Sixty-four tests, the
 * tenant filter and token revocation and the query-count budget among them,
 * never executed there. Nothing was wrong; nothing was checked either.
 *
 * <p>The fix is not a better gate. It is that <b>a test must not decide to skip
 * itself.</b> Which tiers run is a property of the build, so the build owns it:
 *
 * <ul>
 *   <li>{@code mvn test} runs the fast tier, always, in full. Zero skips is the
 *       expected output and {@link SuiteTieringTest} fails the build otherwise.</li>
 *   <li>{@code mvn verify} additionally runs this tier. With no database
 *       reachable it <b>fails</b> rather than passing empty — the outcome a
 *       missing database should produce.</li>
 * </ul>
 *
 * <p>A CI job that cannot reach a database is then <i>absent</i> from the run,
 * which a human notices, instead of <i>green having tested nothing</i>, which
 * nobody does.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Tag("integration")
public @interface IntegrationTest {
}
