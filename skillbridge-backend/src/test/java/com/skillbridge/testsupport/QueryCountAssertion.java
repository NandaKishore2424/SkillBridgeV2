package com.skillbridge.testsupport;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Counts the SQL statements a block of code emits, so an N+1 is a failing test
 * rather than something noticed in production.
 *
 * <p>The point is never the exact number. A test that says "this costs 7
 * queries" gets fixed by changing 7 to 8 the first time someone adds a
 * legitimate query, and from then on it guards nothing. What is worth asserting
 * is that the count is <em>bounded</em> and <em>does not grow with the size of
 * the result set</em> — that is the definition of an N+1, and it survives every
 * legitimate refactor.
 *
 * <p>Counting is done with Hibernate's own {@link Statistics}, which is
 * enabled for every profile in {@code application.yaml}. Statistics are held on
 * the {@code SessionFactory} and are therefore global: this class clears them
 * before each measurement, so two measurements must not run concurrently. The
 * suite runs sequentially, and {@link #countQueries} is deliberately not
 * thread-safe rather than pretending to be.
 *
 * <p>{@code getPrepareStatementCount()} rather than {@code getQueryExecutionCount()}:
 * the latter counts only HQL/criteria queries, missing the entity loads a lazy
 * association triggers — which is precisely the thing being hunted.
 */
@Component
public class QueryCountAssertion {

    private final SessionFactory sessionFactory;

    @Autowired
    QueryCountAssertion(EntityManagerFactory entityManagerFactory) {
        this.sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
    }

    /** Runs {@code block}, returning its value alongside the statements it took. */
    public <T> QueryCountResult<T> countQueries(Supplier<T> block) {
        Statistics stats = sessionFactory.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();

        T value = block.get();

        return new QueryCountResult<>(value, stats.getPrepareStatementCount());
    }

    /** For a block with no useful return value. */
    public QueryCountResult<Void> countQueries(Runnable block) {
        return countQueries(() -> {
            block.run();
            return null;
        });
    }

    public record QueryCountResult<T>(T value, long queryCount) {

        public QueryCountResult<T> assertAtMost(long max) {
            if (queryCount > max) {
                throw new AssertionError(
                        ("Expected at most %d queries but %d were executed. That usually means a "
                                + "lazy association is being traversed in a loop; add a fetch join, "
                                + "an @EntityGraph, or a bulk lookup keyed by the page's own ids. "
                                + "Set logging.level.org.hibernate.SQL=DEBUG to see which.")
                                .formatted(max, queryCount));
            }
            return this;
        }

        /**
         * The invariant that actually matters: the same work over a bigger
         * result set must cost the same number of statements.
         *
         * @param larger the measurement taken over more rows
         */
        public QueryCountResult<T> assertDoesNotGrowInto(QueryCountResult<?> larger) {
            if (larger.queryCount() > queryCount) {
                throw new AssertionError(
                        ("Query count grew with the size of the result set: %d for the smaller "
                                + "page, %d for the larger. That is the definition of an N+1 — "
                                + "something is being loaded per row rather than per page.")
                                .formatted(queryCount, larger.queryCount()));
            }
            return this;
        }
    }
}
