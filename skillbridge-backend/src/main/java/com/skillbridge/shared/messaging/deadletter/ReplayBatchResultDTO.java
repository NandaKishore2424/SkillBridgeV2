package com.skillbridge.shared.messaging.deadletter;

import java.util.List;
import java.util.UUID;

/**
 * What a bulk replay did, or in a dry run would do.
 *
 * @param matched      rows the selection took
 * @param replayed     rows replayed; in a dry run, the rows that would be, with no event id
 * @param skipped      rows not replayed, each with the reason
 * @param limitReached the selection stopped at the limit, so more rows may match; run it again
 */
public record ReplayBatchResultDTO(
        boolean dryRun,
        int matched,
        List<Replayed> replayed,
        List<Skipped> skipped,
        boolean limitReached) {

    /** @param replayEventId the new outbox event; null in a dry run */
    public record Replayed(long id, UUID replayEventId) {
    }

    public record Skipped(long id, String reason) {
    }
}
