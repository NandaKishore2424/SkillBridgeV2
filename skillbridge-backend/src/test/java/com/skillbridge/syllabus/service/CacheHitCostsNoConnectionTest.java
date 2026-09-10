package com.skillbridge.syllabus.service;

import com.skillbridge.common.cache.L1CacheConfig;
import com.skillbridge.testsupport.TenantFixture;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
 * A cache hit must not open a transaction.
 *
 * <p>Statement counts miss this entirely, which is why it took a second
 * instrument to find. A warm read of the curriculum reported <b>0 statements</b>
 * and looked finished — and was still opening a Hibernate session and checking
 * out a connection every time, because the cache advisor sat <em>inside</em> the
 * transaction advisor. Three warm hits, three sessions, zero statements.
 *
 * <p>On this project that is not a rounding error. Phase 05 spent its effort
 * taking {@code GET /admin/students} from 5.00 connection checkouts per request
 * to 1.00 against a pooler that allows fifteen in total, and a cache that saves
 * the queries but not the checkout gives half of that back.
 *
 * <p>It had a second effect that was harder to see and worse: callers queued for
 * a connection <em>before</em> consulting the cache, so the pool quietly
 * throttled a thundering herd. {@link CurriculumStampedeTest} measured 2 loads
 * where it should have measured a pool exhaustion, and would have reported the
 * stampede as mild.
 *
 * <p>Fixed by pinning the cache advisor outside the transaction advisor —
 * {@link L1CacheConfig#CACHE_ADVISOR_ORDER} against
 * {@code TransactionConfig.TRANSACTION_ADVISOR_ORDER}. Both orders are explicit
 * numbers precisely so this relationship is stated rather than inherited from a
 * tie between two defaults, which is what produced the bug.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class CacheHitCostsNoConnectionTest {

    private static final String COLLEGE_CODE = "HITNOCONN";

    @Autowired private CurriculumReader reader;
    @Autowired private CacheManager cacheManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;
    private Long batchId;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(1, 1, 2);
        batchId = fixture.batchIds.get(0);
        cacheManager.getCache(L1CacheConfig.CURRICULUM).clear();
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
        cacheManager.getCache(L1CacheConfig.CURRICULUM).clear();
    }

    @Test
    @DisplayName("a warm read opens no session, so it costs no connection")
    void aHitOpensNoSession() {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);

        stats.clear();
        assertThat(reader.byBatchId(batchId))
                .as("the cold read has to actually load, or the warm counts below mean nothing")
                .hasSize(2);
        long coldSessions = stats.getSessionOpenCount();
        long coldStatements = stats.getPrepareStatementCount();

        stats.clear();
        for (int i = 0; i < 3; i++) {
            assertThat(reader.byBatchId(batchId)).hasSize(2);
        }

        assertThat(coldSessions)
                .as("a miss legitimately opens one session")
                .isEqualTo(1);
        assertThat(coldStatements)
                .as("the modules query and the topics query")
                .isEqualTo(2);

        assertThat(stats.getPrepareStatementCount())
                .as("three hits, no SQL — this part was already true and is not what broke")
                .isZero();

        assertThat(stats.getSessionOpenCount())
                .as("""
                    Zero. This read three before the cache advisor was pinned outside \
                    the transaction advisor: every hit opened a session and took a \
                    connection to run no SQL at all. If this is 3 again, check \
                    L1CacheConfig.CACHE_ADVISOR_ORDER against \
                    TransactionConfig.TRANSACTION_ADVISOR_ORDER — lower value is \
                    outermost, and caching has to be outside.""")
                .isZero();
    }
}
