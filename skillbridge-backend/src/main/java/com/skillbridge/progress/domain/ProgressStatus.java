package com.skillbridge.progress.domain;

/**
 * Per-student status for one syllabus topic.
 *
 * <p>Unlike the enrollment lifecycle, progress transitions are deliberately
 * unrestricted. A trainer must be able to move a student from COMPLETED back to
 * NEEDS_IMPROVEMENT after a second look, or straight from NEEDS_IMPROVEMENT to
 * COMPLETED after a resubmission. A strict state machine here would fight the
 * way teaching actually works.
 *
 * <p>What is enforced instead is that every change is attributed to a trainer
 * and timestamped — see {@code TopicProgress.record}.
 */
public enum ProgressStatus {

    /** No work started. The default for a newly enrolled student. */
    PENDING(0.0),

    /** Student has started; the trainer has seen partial work. */
    IN_PROGRESS(0.5),

    /** Trainer has signed this off. */
    COMPLETED(1.0),

    /** Submitted but not to standard. Counts as attempted, not complete. */
    NEEDS_IMPROVEMENT(0.25);

    private final double completionWeight;

    ProgressStatus(double completionWeight) {
        this.completionWeight = completionWeight;
    }

    /**
     * Weight used for percentage calculations.
     *
     * <p>Deliberately not binary. A student twenty topics into a sixty-topic
     * syllabus with all twenty in progress is not at 0%, and a dashboard that
     * tells them so is actively demotivating. Weighted completion is also a
     * better input to the at-risk report than a raw completed count.
     */
    public double weight() {
        return completionWeight;
    }

    public boolean isTerminal() {
        return this == COMPLETED;
    }

    public boolean needsAttention() {
        return this == NEEDS_IMPROVEMENT;
    }
}
