package com.skillbridge.enrollment.scheduler;

import com.skillbridge.common.scheduling.SingleRunGuard;
import com.skillbridge.enrollment.service.EnrollmentManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic tidying of the enrollment queue.
 *
 * <p>Currently one job: expire applications to batches that have already
 * started. Without it the pending queue grows a tail of applications nobody can
 * sensibly action, and an admin opening the queue has to work out for each one
 * whether it still means anything.
 *
 * <p><b>Safe on more than one node since 2026-09-12.</b> {@code @Scheduled}
 * fires on every instance, so two replicas ran this twice at the same moment.
 * It survived that because the update only matches PENDING rows, but the next
 * thing added here would not have. {@link SingleRunGuard} takes a Postgres
 * advisory lock, so exactly one instance runs it and the others decline.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EnrollmentMaintenanceJob {

    private final EnrollmentManagementService enrollmentService;
    private final SingleRunGuard singleRun;

    /**
     * Runs nightly at 02:15.
     *
     * <p>Deliberately not on the hour: every cron job in every system is written
     * to run on the hour, and clustering them is how a quiet database gets a
     * thundering herd at midnight.
     */
    @Scheduled(cron = "0 15 2 * * *")
    public void expireStaleApplications() {
        try {
            singleRun.runExclusively("enrollment-maintenance", () -> {
                int expired = enrollmentService.expireStaleRequests();
                log.debug("Enrollment maintenance finished; expired {} requests", expired);
            });
        } catch (Exception ex) {
            // A scheduled method that throws is silently dropped by Spring's
            // default error handler, so the failure is logged explicitly here.
            log.error("Enrollment maintenance job failed", ex);
        }
    }
}
