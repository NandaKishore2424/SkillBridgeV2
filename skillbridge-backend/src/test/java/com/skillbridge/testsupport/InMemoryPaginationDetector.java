package com.skillbridge.testsupport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fails a test if Hibernate says it paginated in memory.
 *
 * <p>Applying {@code LIMIT}/{@code OFFSET} to a query that fetch-joins a
 * collection is impossible in SQL — the row count no longer matches the entity
 * count — so Hibernate reads <em>every</em> matching row, materialises every
 * entity, and slices the list in the heap. On a large table that is an
 * {@code OutOfMemoryError}; on a small one it is invisible.
 *
 * <p>What makes it worth a detector rather than a code review is that nothing
 * fails. The endpoint returns the right page. The only signal is a single
 * warning line — {@code HHH90003004} on Hibernate 6, {@code HHH000104} on 5 —
 * in a log nobody reads, and this codebase has grepped for it by hand three
 * times already. Both codes are matched, since the message is what is stable
 * across versions, not the logger it comes from.
 *
 * <p><b>The query-count assertion does not cover this.</b> Verified by adding a
 * collection fetch to the Specification behind {@code GET /admin/batches}:
 * Hibernate read every row and sliced in the heap, and the statement count
 * stayed flat at 5 for both the small and the large tenant — because it is
 * still one query, just a ruinous one. The counter sees statements; only this
 * sees what the statement did.
 */
public final class InMemoryPaginationDetector implements AutoCloseable {

    /** Hibernate's two spellings of "I gave up and did this in memory". */
    private static final List<String> CODES = List.of("HHH90003004", "HHH000104");

    private final Logger hibernate = (Logger) LoggerFactory.getLogger("org.hibernate");
    private final List<String> offences = new CopyOnWriteArrayList<>();
    private final AppenderBase<ILoggingEvent> appender;
    private final Level originalLevel;

    public InMemoryPaginationDetector() {
        this.originalLevel = hibernate.getLevel();
        // The warning is logged at WARN, but the logger may be configured
        // higher; force it down for the duration or the detector watches a
        // stream that has already been filtered.
        hibernate.setLevel(Level.WARN);

        this.appender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                String message = event.getFormattedMessage();
                if (CODES.stream().anyMatch(message::contains)) {
                    offences.add(message);
                }
            }
        };
        appender.setName("in-memory-pagination-detector");
        appender.start();
        hibernate.addAppender(appender);
    }

    /** Throws if Hibernate paginated in memory since construction. */
    public void assertNone(String what) {
        if (!offences.isEmpty()) {
            throw new AssertionError(
                    ("%s paginated in memory. Hibernate cannot push LIMIT/OFFSET through a "
                            + "collection fetch, so it read every matching row and sliced the list "
                            + "in the heap — correct output, unbounded memory. Fetch the "
                            + "association separately, or page the ids first and load the page's "
                            + "entities by id.%n  %s")
                            .formatted(what, String.join("%n  ".formatted(), offences)));
        }
    }

    @Override
    public void close() {
        hibernate.detachAppender(appender);
        appender.stop();
        hibernate.setLevel(originalLevel);
    }
}
