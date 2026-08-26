package com.skillbridge.enrollment.domain;

/**
 * State of an actual enrollment, as distinct from the request that created it.
 *
 * <p>Kept separate from {@link EnrollmentStatus} deliberately: a request is a
 * piece of workflow that ends, while an enrollment is a standing relationship
 * that has its own life afterwards. Collapsing the two is how you end up unable
 * to answer "did this student finish the batch?" without reading history.
 */
public enum EnrollmentState {

    /** Currently studying in the batch. */
    ACTIVE,

    /** Finished the batch. */
    COMPLETED,

    /** Left, or was removed, before the batch ended. */
    DROPPED
}
