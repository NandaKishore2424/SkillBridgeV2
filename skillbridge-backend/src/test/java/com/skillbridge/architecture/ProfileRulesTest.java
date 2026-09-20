package com.skillbridge.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A test run uses the {@code test} profile and nothing else.
 *
 * <p>Until 2026-09-20 the build activated {@code local,test}. {@code local} is a
 * developer's gitignored {@code application-local.yaml}: no two machines share
 * it and CI has none, so every run depended on settings nobody could reproduce.
 * It was not theoretical -- once mail became real, that file pointed the test
 * suite at whatever SMTP server the developer was using, and only a pinned
 * {@code app.mail.provider} in {@code application-test.yaml} kept messages from
 * leaving.
 *
 * <p>Reads the property the build sets (pom.xml, both tiers) rather than a
 * Spring context, so it runs in the fast tier and fails for either tier.
 */
class ProfileRulesTest {

    @Test
    @DisplayName("the build activates only the test profile")
    void onlyTheTestProfile() {
        List<String> active = List.of(new StandardEnvironment().getActiveProfiles());

        assertThat(active)
                .withFailMessage("""
                        Active profiles during this test run: %s

                        Expected exactly [test]. `local` is a developer's private, \
                        gitignored configuration; a suite that loads it tests something \
                        CI cannot reproduce. Set it in pom.xml's surefire and failsafe \
                        configuration.""", active)
                .containsExactly("test");
    }
}
