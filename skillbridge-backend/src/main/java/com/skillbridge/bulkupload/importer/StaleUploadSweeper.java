package com.skillbridge.bulkupload.importer;

import com.skillbridge.bulkupload.repository.BulkUploadRepository;
import com.skillbridge.common.scheduling.SingleRunGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Fails uploads whose importer has died.
 *
 * <p>A crash or {@code kill -9} mid-import left the upload PROCESSING for ever,
 * and the screen said so. The importer now beats ({@code last_progress_at})
 * every {@value BulkUploadJob#PROGRESS_EVERY} rows; an upload that has not beaten
 * for {@code app.import.stale-after} is declared dead. Rows already imported are
 * kept, and a FAILED upload is outside the same-file index, so the admin can
 * upload the file again.
 */
@Component
@Slf4j
public class StaleUploadSweeper {

    static final String REASON = "The import stopped reporting progress, usually because the server restarted. "
            + "Rows already imported are kept; upload the file again to import the rest.";

    private final BulkUploadRepository uploads;
    private final SingleRunGuard singleRun;
    private final Duration staleAfter;
    private final boolean scheduled;

    public StaleUploadSweeper(BulkUploadRepository uploads, SingleRunGuard singleRun,
                              @Value("${app.import.stale-after:10m}") Duration staleAfter,
                              @Value("${app.import.sweeper.enabled:true}") boolean scheduled) {
        this.uploads = uploads;
        this.singleRun = singleRun;
        this.staleAfter = staleAfter;
        this.scheduled = scheduled;
    }

    /**
     * Off under the test profile, like the outbox relay: a background UPDATE
     * lands in the Hibernate statement count of whatever test is measuring at
     * that moment. It made CurriculumStampedeTest count 3 instead of 2, twice
     * in full runs, and never alone. Tests call {@link #sweepNow()}.
     */
    @Scheduled(fixedDelayString = "${app.import.sweep-interval-ms:60000}")
    public void sweep() {
        if (scheduled) {
            sweepNow();
        }
    }

    public void sweepNow() {
        singleRun.runExclusively("stale-upload-sweep", this::failStale);
    }

    /** @return how many uploads were failed */
    int failStale() {
        LocalDateTime now = LocalDateTime.now();
        int failed = uploads.failStale(now.minus(staleAfter), REASON, now);
        if (failed > 0) {
            log.warn("Marked {} stalled upload(s) FAILED (no progress for {})", failed, staleAfter);
        }
        return failed;
    }
}
