package com.skillbridge.common.tenant;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the tenant filter is actually applied to queries.
 *
 * <p>This test exists because the thing it checks failed silently for months.
 * The original implementation enabled {@code collegeFilter} from a servlet
 * filter, on a session no query used once {@code open-in-view} was turned off.
 * Nothing threw, and with a single college in the database there was nothing
 * visible to leak, so it went unnoticed until a soft-delete filter added the
 * same way failed to hide a deleted row.
 *
 * <p>Two things the test has to get right, both learned by getting them wrong:
 *
 * <ul>
 *   <li>It reads through {@link TenantScopedProbe}, a real bean with a real
 *       {@code @Transactional} method. Putting {@code @Transactional} on the
 *       test method instead does not work: that is handled by Spring's
 *       {@code TransactionalTestExecutionListener}, not by the transaction
 *       advisor, so {@link TenantFilterAspect} never runs and the test proves
 *       nothing about production.</li>
 *   <li>It asserts on {@link BatchRepository#findAll()}, which carries no
 *       college predicate. Every other list query in the application names its
 *       college explicitly and passes whether or not the filter works — which
 *       is precisely why none of them caught this.</li>
 * </ul>
 *
 * <p>It needs at least two colleges with batches to mean anything, so it creates
 * <b>both</b> of them and removes them afterwards. Depending on whatever happens
 * to be in the database would make it pass vacuously on a single-tenant
 * instance — which is the state the original bug hid in.
 *
 * <p>It used to create only one and borrow the other from whatever was already
 * there, while this comment claimed it was self-sufficient. Against the live
 * database that was invisible: there was always a second college with batches.
 * The first run against an empty container failed with <i>"needs batches in at
 * least two colleges to prove scoping; found 1"</i> — its own precondition,
 * which had never once been the thing it was measuring. <b>A test that asserts
 * its precondition still has to establish it</b>, or the assertion only says
 * what the database happened to contain.
 */
@SpringBootTest
@IntegrationTest
class TenantFilterAspectTest {

    private static final String FIXTURE_CODE_A = "TNTFILTERTESTA";
    private static final String FIXTURE_CODE_B = "TNTFILTERTESTB";

    @Autowired
    private TenantScopedProbe probe;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Two throwaway colleges, each with one batch.
     *
     * <p><b>Both</b>, not one. Scoping cannot be demonstrated with a single
     * tenant: a filtered query and an unfiltered one return the same rows, and
     * the test passes either way. Creating only one and letting the database
     * supply the other is how this test came to depend on live data while its
     * own comment said it did not.
     *
     * <p>Written with JDBC rather than the repositories on purpose: the entities
     * carry {@code @SQLRestriction} and a tenant filter, and a fixture that has
     * to dodge the very mechanisms under test is not a fixture worth trusting.
     */
    @BeforeEach
    void createTwoColleges() {
        removeFixture();
        seedCollegeWithBatch(FIXTURE_CODE_A);
        seedCollegeWithBatch(FIXTURE_CODE_B);
    }

    private void seedCollegeWithBatch(String code) {
        jdbc.update("""
                INSERT INTO colleges (name, code, email, phone, address, status, created_at, updated_at)
                VALUES ('Tenant Filter Test College ' || ?, ?, 'tenant-filter@example.invalid',
                        '0000000000', 'n/a', 'ACTIVE', now(), now())
                """, code, code);
        jdbc.update("""
                INSERT INTO batches (college_id, name, description, status,
                                     start_date, end_date, created_at, updated_at, version)
                SELECT id, 'Tenant Filter Test Batch', 'fixture', 'UPCOMING',
                       CURRENT_DATE, CURRENT_DATE + 30, now(), now(), 0
                FROM colleges WHERE code = ?
                """, code);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        removeFixture();
    }

    private void removeFixture() {
        for (String code : new String[]{FIXTURE_CODE_A, FIXTURE_CODE_B}) {
            jdbc.update("DELETE FROM batches WHERE college_id IN (SELECT id FROM colleges WHERE code = ?)",
                    code);
            jdbc.update("DELETE FROM colleges WHERE code = ?", code);
        }
    }

    /** The college ids this test created, in insertion order. */
    private List<Long> fixtureCollegeIds() {
        return jdbc.queryForList(
                "SELECT id FROM colleges WHERE code IN (?, ?) ORDER BY id",
                Long.class, FIXTURE_CODE_A, FIXTURE_CODE_B);
    }

    @Test
    @DisplayName("a college-scoped caller sees only their own college's batches")
    void filterScopesAnUnqualifiedQuery() {
        List<Long> fixtures = fixtureCollegeIds();
        assertThat(fixtures)
                .as("the fixture must create both colleges itself; borrowing one from the "
                        + "database is how this test used to pass vacuously")
                .hasSize(2);
        Long mine = fixtures.get(0);
        Long theirs = fixtures.get(1);

        authenticateAs(null, "SYSTEM_ADMIN");
        assertThat(probe.collegeIdsOfAllBatches())
                .as("unscoped, both fixture colleges' batches are visible")
                .contains(mine, theirs);

        authenticateAs(mine, "COLLEGE_ADMIN");

        assertThat(probe.collegeIdsOfAllBatches())
                .as("findAll() has no college predicate, so anything here came through the filter")
                .isNotEmpty()
                .containsOnly(mine)
                .doesNotContain(theirs);
    }

    @Test
    @DisplayName("a SYSTEM_ADMIN is deliberately unscoped")
    void systemAdminSeesEveryCollege() {
        List<Long> fixtures = fixtureCollegeIds();
        assertThat(fixtures).hasSize(2);

        authenticateAs(null, "SYSTEM_ADMIN");
        assertThat(probe.collegeIdsOfAllBatches())
                .as("a system admin must not be tenant-filtered")
                .contains(fixtures.get(0), fixtures.get(1));
    }

    private void authenticateAs(Long collegeId, String roleName) {
        SecurityContextHolder.clearContext();

        Role role = new Role();
        role.setName(roleName);

        User user = User.builder()
                .id(-1L)
                .email("filter-test@example.invalid")
                .collegeId(collegeId)
                .isActive(true)
                .roles(Set.of(role))
                .build();

        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /**
     * A transactional bean, so {@link TenantFilterAspect} actually advises the
     * call the way it advises a service in production.
     */
    static class TenantScopedProbe {

        private final BatchRepository batchRepository;

        TenantScopedProbe(BatchRepository batchRepository) {
            this.batchRepository = batchRepository;
        }

        @Transactional(readOnly = true)
        public List<Long> collegeIdsOfAllBatches() {
            return batchRepository.findAll().stream()
                    .map(Batch::getCollege)
                    .map(c -> c.getId())
                    .toList();
        }
    }

    @TestConfiguration
    static class ProbeConfig {
        @Bean
        TenantScopedProbe tenantScopedProbe(BatchRepository batchRepository) {
            return new TenantScopedProbe(batchRepository);
        }
    }
}
