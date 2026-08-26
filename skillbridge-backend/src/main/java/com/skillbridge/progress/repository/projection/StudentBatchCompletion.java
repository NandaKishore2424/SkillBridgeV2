package com.skillbridge.progress.repository.projection;

/** One row of the trainer's batch-wide completion summary. */
public interface StudentBatchCompletion {

    Long getStudentId();

    int getTotal();

    int getCompleted();

    Double getWeightedSum();
}
