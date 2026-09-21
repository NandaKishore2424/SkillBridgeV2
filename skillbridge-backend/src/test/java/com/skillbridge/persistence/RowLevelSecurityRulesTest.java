package com.skillbridge.persistence;

import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which tables have row-level security, and what that does and does not mean.
 *
 * <p>Three do: the job corpus, the deduplication table and the dead-letter
 * table. None of them has a policy, and none forces RLS on its owner, so for
 * the application -- which owns them -- the flag changes nothing at all. It
 * bites any other role: a write is refused with a clear error, and <b>a SELECT
 * returns zero rows, silently</b>. That is the failure this project has been
 * bitten by before, and it is why the AI service's role
 * ({@code scripts/db/ai-service-role.sql}) is granted explicit policies.
 *
 * <p>It is pinned here because it drifted: the restored database had RLS on
 * {@code industry_job_descriptions} and the migrations did not (V10 fixed it),
 * which no schema fingerprint had compared.
 *
 * <p>The tenant tables are deliberately absent. Scoping is enforced in the
 * application (TenantGuard, the Hibernate filter, and CrossTenantAccessTest
 * over the whole route table); doing it again in the database would mean a
 * per-request {@code SET LOCAL} and policies on thirty tables. That is a real
 * option, written up in docs/SECURITY.md, not an oversight.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@IntegrationTest
class RowLevelSecurityRulesTest {

    private static final List<String> WITH_RLS =
            List.of("dead_letter_events", "industry_job_descriptions", "processed_events");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("exactly three tables have row-level security, and it is not forced on the owner")
    void theSetIsWhatWeThinkItIs() {
        List<String> enabled = jdbc.queryForList("""
                SELECT relname FROM pg_class
                WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace AND relrowsecurity
                ORDER BY relname
                """, String.class);

        assertThat(enabled)
                .withFailMessage("""
                        Row-level security is enabled on a different set of tables than expected:

                          expected %s
                          found    %s

                        Enabling it on a table nobody wrote a policy for makes that table \
                        unreadable -- silently, zero rows -- for every role except its owner. \
                        If this is deliberate, update this list and say why.""", WITH_RLS, enabled)
                .isEqualTo(WITH_RLS);

        assertThat(jdbc.queryForList("""
                SELECT relname FROM pg_class
                WHERE relkind = 'r' AND relnamespace = 'public'::regnamespace AND relforcerowsecurity
                """, String.class))
                .as("FORCE would apply RLS to the owner too, which is the application itself")
                .isEmpty();
    }

    @Test
    @DisplayName("none of them has a policy, so only an explicit grant of one lets another role in")
    void noPoliciesExistYet() {
        assertThat(jdbc.queryForList("SELECT tablename || '.' || policyname FROM pg_policies "
                + "WHERE schemaname = 'public'", String.class))
                .as("a policy added without updating this test's javadoc would hide what RLS is doing here")
                .isEmpty();
    }
}
