package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Which dead letters a bulk replay should take.
 *
 * <p>Either {@code ids} or {@code eventType}, or neither (every PENDING row, up to
 * {@code limit}) — never both, because "these ids, but only if they are of this
 * type" is a filter nobody means and everybody misreads.
 *
 * <p><b>{@code dryRun} defaults to true.</b> A bulk replay re-executes side effects
 * for every row it takes; forgetting a flag must not be how that happens. Send
 * {@code "dryRun": false} to replay.
 */
public record ReplayBatchRequest(
        @Size(max = DeadLetterService.MAX_BATCH) List<Long> ids,
        @Size(max = 100) String eventType,
        @Min(1) @Max(DeadLetterService.MAX_BATCH) Integer limit,
        Boolean dryRun) {

    @JsonIgnore
    public boolean isDryRunRequested() {
        return dryRun == null || dryRun;
    }

    @JsonIgnore
    public int effectiveLimit() {
        return limit != null ? limit : DeadLetterService.DEFAULT_BATCH;
    }

    @JsonIgnore
    public boolean hasIds() {
        return ids != null && !ids.isEmpty();
    }

    @JsonIgnore
    @AssertTrue(message = "give ids or eventType, not both")
    public boolean isOneSelector() {
        return !(hasIds() && eventType != null);
    }
}
