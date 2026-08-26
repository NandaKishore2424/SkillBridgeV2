package com.skillbridge.enrollment.repository.projection;

/**
 * Closed projection over the student dashboard aggregate.
 *
 * <p>Spring Data maps the result set straight onto these getters; no entity is
 * built and no persistence context is touched.
 */
public interface StudentStatsProjection {

    int getTotalEnrolled();

    int getActiveCount();

    int getCompletedCount();

    int getUpcomingCount();

    int getPendingRequests();

    int getTopicsCompleted();

    int getTopicsAssigned();
}
