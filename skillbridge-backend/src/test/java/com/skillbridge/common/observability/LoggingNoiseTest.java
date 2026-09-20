package com.skillbridge.common.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loggers the shipped configuration must keep quiet.
 *
 * <p>A log nobody can read is the same as no log, and this one had become
 * exactly that. {@code generate_statistics: true} makes Hibernate's
 * {@code StatisticalLoggingSessionEventListener} write a thirteen-line "Session
 * Metrics" block at INFO for <em>every</em> session, and the outbox relay opens
 * one twice a second. Measured against the running application on 2026-09-20:
 * roughly a hundred lines a minute on an idle system, into which a real error
 * disappears.
 *
 * <p>The configuration already tried to stop it. {@code application.yaml} set
 * {@code org.hibernate.stat: WARN} under a comment saying the per-session
 * summary was "not wanted in normal output" — and that is a different package
 * from {@code org.hibernate.engine.internal}, where the listener actually
 * lives. The setting muted nothing, and the comment claimed it worked, which is
 * why it survived.
 *
 * <p><b>Why this reads the file rather than asking a running context.</b>
 * Every {@code @SpringBootTest} here runs under the {@code test} profile, and
 * {@code application-test.yaml} deliberately raises {@code org.hibernate.stat}
 * to DEBUG — the statement-count guards need it. So the only place the shipped
 * levels exist is {@code application.yaml}, and that is what ships.
 */
class LoggingNoiseTest {

    /**
     * Loggers whose INFO output is per-session or per-statement chatter.
     *
     * <p>Named by string because these are Hibernate's own internal classes,
     * not public API on the compile classpath. The second one is the class that
     * actually writes the summary; the first is the package the original
     * setting reached for and is kept because it is genuinely noisy too.
     */
    private static final String[] MUST_BE_QUIET = {
            "org.hibernate.stat",
            "org.hibernate.engine.internal.StatisticalLoggingSessionEventListener",
    };

    private static final String[] QUIET_LEVELS = {"WARN", "ERROR", "OFF"};

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loggingLevels() {
        try (InputStream yaml = LoggingNoiseTest.class.getResourceAsStream("/application.yaml")) {
            // Multiple documents: application.yaml is one, but load all so a
            // future `---` split does not make this silently read half the file.
            for (Object document : new Yaml().loadAll(yaml)) {
                Map<String, Object> root = (Map<String, Object>) document;
                Map<String, Object> logging = (Map<String, Object>) root.get("logging");
                if (logging != null && logging.get("level") != null) {
                    return (Map<String, Object>) logging.get("level");
                }
            }
        } catch (Exception e) {
            throw new AssertionError("could not read application.yaml", e);
        }
        throw new AssertionError("application.yaml has no logging.level block at all");
    }

    @Test
    @DisplayName("the per-session statistics summary is muted in the shipped config")
    void statisticsSummaryIsMuted() {
        Map<String, Object> levels = loggingLevels();

        for (String logger : MUST_BE_QUIET) {
            assertThat(levels)
                    .as("application.yaml does not set a level for %s", logger)
                    .containsKey(logger);
            assertThat(String.valueOf(levels.get(logger)).toUpperCase())
                    .as("%s at INFO is ~100 lines a minute on an idle system", logger)
                    .isIn((Object[]) QUIET_LEVELS);
        }
    }

    @Test
    @DisplayName("and nothing mutes the application's own logs to get there")
    void theApplicationItselfIsNotSilenced() {
        // The overcorrection this stops: muting a package broad enough to take
        // the audit trail and the request log with it.
        Map<String, Object> levels = loggingLevels();

        for (String logger : levels.keySet()) {
            if (logger.startsWith("com.skillbridge") || logger.equals("root")) {
                assertThat(String.valueOf(levels.get(logger)).toUpperCase())
                        .as("%s must still reach INFO", logger)
                        .isIn("TRACE", "DEBUG", "INFO");
            }
        }
    }
}
