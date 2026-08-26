package com.skillbridge.progress.repository.projection;

import java.time.LocalDateTime;

/**
 * Closed projection over the per-(student, batch) aggregate query.
 *
 * <p>Spring Data maps the result set straight onto these getters — no entity is
 * instantiated and no persistence context is involved, so this stays cheap even
 * when it runs for every enrolled batch on a dashboard load.
 */
public interface BatchProgressAggregate {

    int getTotal();

    int getCompleted();

    int getInProgress();

    int getNeedsWork();

    /**
     * Sum of per-status weights. Divide by {@link #getTotal()} for the fraction;
     * the division is done in Java so a zero-topic batch reads as 0% rather than
     * producing a division by zero in SQL.
     */
    Double getWeightedSum();

    /** Null when no row in the batch carries a numeric score. */
    Double getAverageScore();

    LocalDateTime getLastActivityAt();
}
