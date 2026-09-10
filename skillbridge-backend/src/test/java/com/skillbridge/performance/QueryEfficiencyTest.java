package com.skillbridge.performance;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.batch.controller.BatchController;
import com.skillbridge.student.controller.StudentAdminController;
import com.skillbridge.student.service.StudentDashboardService;
import com.skillbridge.syllabus.service.SyllabusService;
import com.skillbridge.testsupport.InMemoryPaginationDetector;
import com.skillbridge.testsupport.QueryCountAssertion;
import com.skillbridge.testsupport.TenantFixture;
import com.skillbridge.trainer.service.TrainerDashboardService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.skillbridge.common.dto.Pagination.of;

/**
 * Measures how many SQL statements each read path emits, and fails when that
 * number grows with the size of the result set.
 *
 * <p>An N+1 is invisible in development and fatal in production: three batches
 * cost four queries, three hundred cost three hundred and one, and nothing in
 * between tells you. The only reliable guard is a test that measures.
 *
 * <p>Two things this deliberately does <em>not</em> assert:
 *
 * <ul>
 *   <li><b>Exact counts, as the primary assertion.</b> "This costs 7 queries"
 *       gets fixed by changing 7 to 8 the first time someone adds a legitimate
 *       query, and guards nothing thereafter. The load-bearing assertion is
 *       that the count is the same over a bigger page. Budgets are asserted too,
 *       but generously, as a second line.</li>
 *   <li><b>Wall-clock time.</b> That belongs to Task 7 at realistic volume, and
 *       measuring it here against a remote database would produce a flaky test
 *       about the network.</li>
 * </ul>
 *
 * <p>The counts printed by this class are the source for the table in
 * {@code docs/PERFORMANCE_BUDGET.md}. Re-run it after changing a read path.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class QueryEfficiencyTest {

    private static final String SMALL_CODE = "PERFSMALL";
    private static final String LARGE_CODE = "PERFLARGE";

    /** Counts observed, printed at the end for the budget document. */
    private static final Map<String, String> OBSERVED = new LinkedHashMap<>();

    @Autowired private JdbcTemplate jdbc;
    @Autowired private QueryCountAssertion queries;
    @Autowired private BatchController batchController;
    @Autowired private StudentAdminController studentAdminController;
    @Autowired private TrainerDashboardService trainerDashboardService;
    @Autowired private StudentDashboardService studentDashboardService;
    @Autowired private SyllabusService syllabusService;
    @Autowired private org.springframework.cache.CacheManager cacheManager;

    private TenantFixture small;
    private TenantFixture large;

    @BeforeEach
    void seed() {
        if (small != null) {
            return;
        }
        // Two tenants rather than two sizes of one, so a measurement never sees
        // the other's rows: the college scope is what keeps them apart.
        // The large tenant is bigger in every dimension the endpoints page
        // over: more batches, more students, AND a deeper curriculum. Leaving
        // the tree the same size in both is how the syllabus measurement looked
        // flat while it was one query per sub-module.
        // Sized to expose an N+1, not to be realistic. The signal is that the
        // large tenant is bigger in every dimension the endpoints walk; going
        // bigger only buys round trips to a remote database, and this fixture
        // was once heavy enough to make the whole suite flaky.
        small = new TenantFixture(jdbc, SMALL_CODE);
        small.seed(2, 2, 2);
        large = new TenantFixture(jdbc, LARGE_CODE);
        large.seed(8, 6, 6);
    }

    @AfterAll
    void tearDown() {
        if (small != null) {
            small.remove();
        }
        if (large != null) {
            large.remove();
        }
        SecurityContextHolder.clearContext();

        // Measurements are printed as they are taken; see record().
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("GET /admin/batches — query count does not grow with the page")
    void adminBatchList() {
        compare("GET /admin/batches",
                () -> {
                    asCollegeAdmin(small.collegeId);
                    return batchController.getAllBatches(0, 20, null, null, null);
                },
                () -> {
                    asCollegeAdmin(large.collegeId);
                    return batchController.getAllBatches(0, 20, null, null, null);
                });
    }

    @Test
    @DisplayName("GET /admin/students — query count does not grow with the page")
    void adminStudentList() {
        compare("GET /admin/students",
                () -> {
                    asCollegeAdmin(small.collegeId);
                    return studentAdminController.getAllStudents(0, 20, null, null, null);
                },
                () -> {
                    asCollegeAdmin(large.collegeId);
                    return studentAdminController.getAllStudents(0, 20, null, null, null);
                });
    }

    @Test
    @DisplayName("GET /trainer/batches — query count does not grow with the trainer's batch count")
    void trainerBatchList() {
        compare("GET /trainer/batches",
                () -> trainerDashboardService.getTrainerBatches(small.trainerUserId, of(0, 20)),
                () -> trainerDashboardService.getTrainerBatches(large.trainerUserId, of(0, 20)));
    }

    @Test
    @DisplayName("GET /student/dashboard/stats — bounded regardless of enrollments")
    void studentDashboardStats() {
        compare("GET /student/dashboard/stats",
                () -> studentDashboardService.getDashboardStats(small.studentUserIds.get(0)),
                () -> studentDashboardService.getDashboardStats(large.studentUserIds.get(0)));
    }

    @Test
    @DisplayName("GET /student/batches — query count does not grow with enrollments")
    void studentEnrolledBatches() {
        compare("GET /student/batches",
                () -> studentDashboardService.getStudentBatches(small.studentUserIds.get(0), of(0, 20)),
                () -> studentDashboardService.getStudentBatches(large.studentUserIds.get(0), of(0, 20)));
    }

    /**
     * This one reads through a cache now, so it clears it first.
     *
     * <p>Without that the measurement quietly stops being a measurement. A warm
     * key costs 1 statement rather than 3, and this test asserts that the count
     * does not <em>grow</em> — so 1 against 1 passes just as happily as 3 against
     * 3, and the N+1 guard on the curriculum tree would evaporate the first time
     * something warmed the key before it. The cache is the right answer in
     * production and the wrong instrument here: what this test exists to watch is
     * the path to the database.
     */
    @Test
    @DisplayName("GET /batches/{id}/syllabus — bounded regardless of tree size")
    void syllabusTree() {
        try (var detector = new InMemoryPaginationDetector()) {
            cacheManager.getCache(com.skillbridge.common.cache.L1CacheConfig.CURRICULUM).clear();
            asCollegeAdmin(small.collegeId);
            var smallCount = queries.countQueries(
                    () -> syllabusService.getCurriculumByBatchId(small.batchIds.get(0)));
            cacheManager.getCache(com.skillbridge.common.cache.L1CacheConfig.CURRICULUM).clear();
            asCollegeAdmin(large.collegeId);
            var largeCount = queries.countQueries(
                    () -> syllabusService.getCurriculumByBatchId(large.batchIds.get(0)));

            record("GET /batches/{id}/syllabus", smallCount.queryCount(), largeCount.queryCount());
            detector.assertNone("GET /batches/{id}/syllabus");
            smallCount.assertDoesNotGrowInto(largeCount);
        }
    }

    @Test
    @DisplayName("GET /admin/batches/{id} — a single batch is a bounded read")
    void adminBatchById() {
        asCollegeAdmin(small.collegeId);
        var result = queries.countQueries(() -> batchController.getBatchById(small.batchIds.get(0)));
        record("GET /admin/batches/{id}", result.queryCount(), result.queryCount());
        result.assertAtMost(6);
    }

    // ------------------------------------------------------------------

    /**
     * Measures the same call over a small and a large tenant and asserts the
     * count did not grow.
     */
    private void compare(String label, java.util.function.Supplier<?> onSmall,
                         java.util.function.Supplier<?> onLarge) {
        // Watches for Hibernate paginating in the heap, which produces the
        // right page and unbounded memory and fails nothing on its own.
        try (var detector = new InMemoryPaginationDetector()) {
            var smallCount = queries.countQueries(onSmall);
            var largeCount = queries.countQueries(onLarge);
            record(label, smallCount.queryCount(), largeCount.queryCount());
            detector.assertNone(label);
            smallCount.assertDoesNotGrowInto(largeCount);
        }
    }

    /**
     * Records and prints a measurement.
     *
     * <p>Printed here rather than collected and dumped in {@code @AfterAll}:
     * Surefire has stopped capturing stdout by the time that runs, so the
     * numbers vanished exactly when the run was green and nobody was looking.
     */
    private void record(String label, long small, long large) {
        String line = "small: " + small + "   |   large: " + large
                + (large > small ? "   <-- GROWS" : "");
        OBSERVED.put(label, line);
        System.out.printf("PERF   %-34s %s%n", label, line);
    }

    private void asCollegeAdmin(Long collegeId) {
        SecurityContextHolder.clearContext();
        Role role = new Role();
        role.setName("COLLEGE_ADMIN");
        User user = User.builder()
                .id(-1L).email("perf-admin@example.invalid").collegeId(collegeId)
                .isActive(true).roles(Set.of(role)).build();
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
