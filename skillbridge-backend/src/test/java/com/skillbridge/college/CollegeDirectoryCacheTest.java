package com.skillbridge.college;

import com.skillbridge.college.dto.CollegeDTO;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.college.service.CollegeDirectoryService;
import com.skillbridge.common.cache.L1CacheConfig;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.testsupport.QueryCountAssertion;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The active-college cache has to actually cache, and has to actually let go.
 *
 * <p>Both halves are worth a test for the same reason: neither failure announces
 * itself. A {@code @Cacheable} that is never applied — a self-invocation, a
 * missing {@code @EnableCaching}, a bean that is not proxied — behaves exactly
 * like a correct one, only slower, and the endpoint keeps answering 200. This
 * project has shipped that shape of nothing-happening before: an
 * {@code application-test.yaml} nothing loaded, whose small connection pool and
 * SQL logging were configuration with no effect (gotcha 9).
 *
 * <p>The stale half is the one that matters in production. This list is served
 * to the unauthenticated registration form; if a write does not evict, a
 * deactivated college stays on that form for up to thirty minutes and nothing
 * anywhere reports a problem.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class CollegeDirectoryCacheTest {

    private static final String COLLEGE_CODE = "CACHEDDIR";

    @Autowired private CollegeDirectoryService directory;
    @Autowired private CollegeRepository collegeRepository;
    @Autowired private QueryCountAssertion queries;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;

    @BeforeEach
    void seed() {
        // No batches or students -- this test only needs the college row, which
        // TenantFixture creates ACTIVE.
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(0, 0);
        clearCache();
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
        clearCache();
    }

    private void clearCache() {
        cacheManager.getCache(L1CacheConfig.ACTIVE_COLLEGES).clear();
    }

    @Test
    @DisplayName("the second read of a page costs no statements at all")
    void aSecondReadIsServedFromMemory() {
        var cold = queries.countQueries(() -> directory.activeColleges(0, 50));
        var warm = queries.countQueries(() -> directory.activeColleges(0, 50));

        assertThat(cold.value().getItems())
                .as("an empty list would make the statement counts below meaningless — "
                        + "the fixture's college is ACTIVE and must be in here")
                .extracting(CollegeDTO::getCode)
                .contains(COLLEGE_CODE);

        assertThat(cold.queryCount())
                .as("the first read has to reach the database, or there is nothing to cache")
                .isPositive();

        assertThat(warm.queryCount())
                .as("""
                    Zero, not fewer. A @Cacheable that is never applied -- self-invoked, \
                    unproxied, or on a context with no @EnableCaching -- reads the \
                    database again and looks identical from the outside.""")
                .isZero();

        assertThat(warm.value().getItems())
                .as("the cached answer has to be the same answer")
                .hasSameSizeAs(cold.value().getItems());
    }

    @Test
    @DisplayName("a different page is a different key, not a hit on the first one")
    void pagesDoNotShareAKey() {
        directory.activeColleges(0, 50);

        var otherPage = queries.countQueries(() -> directory.activeColleges(0, 1));

        assertThat(otherPage.queryCount())
                .as("page 0 size 1 sharing page 0 size 50's entry would serve the wrong "
                        + "number of rows under the right-looking key")
                .isPositive();
        assertThat(otherPage.value().getItems()).hasSize(1);
    }

    @Test
    @DisplayName("deactivating a college takes it off the public list immediately")
    void aWriteEvicts() {
        PagedResponse<CollegeDTO> before = directory.activeColleges(0, 50);
        assertThat(before.getItems()).extracting(CollegeDTO::getCode).contains(COLLEGE_CODE);

        College college = collegeRepository.findById(fixture.collegeId).orElseThrow();
        college.setStatus("INACTIVE");
        directory.save(college);

        assertThat(directory.activeColleges(0, 50).getItems())
                .as("""
                    This is the failure that would not announce itself: the write \
                    succeeds, the endpoint answers 200, and an unauthenticated \
                    registration form offers a college that is no longer open for \
                    half an hour.""")
                .extracting(CollegeDTO::getCode)
                .doesNotContain(COLLEGE_CODE);
    }
}
