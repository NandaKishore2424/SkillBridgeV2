package com.skillbridge.batch.dto;

import jakarta.validation.constraints.Size;

/**
 * Change a batch. Every field is optional and only the ones present are
 * applied: the screen sends a partial body, and until 2026-09-20 an omitted
 * name overwrote the stored one with null.
 *
 * <p>A field that is present must still be valid -- {@code ""} as a name is a
 * 400, not a way to blank it.
 */
public class UpdateBatchRequest {

    @Size(max = 255, message = "Name cannot exceed 255 characters")
    public String name;

    @Size(max = 2000, message = "Description cannot exceed 2000 characters")
    public String description;

    public String status;

    public String startDate;

    public String endDate;

    public Integer maxEnrollments;
}
