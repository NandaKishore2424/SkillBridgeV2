package com.skillbridge.batch.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create a batch. Dates arrive as text because the browser sends
 * {@code yyyy-MM-dd} and older clients sent {@code MM/dd/yyyy};
 * {@code BatchService} parses both and refuses anything else.
 */
public class CreateBatchRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 255, message = "Name cannot exceed 255 characters")
    public String name;

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    public String description;

    /** One of BatchService.STATUSES; defaults to UPCOMING. */
    public String status;

    public String startDate;

    public String endDate;

    /** Accepted and ignored: batches have no capacity column. */
    public Integer maxEnrollments;
}
