package com.skillbridge.batch.dto;

import jakarta.validation.constraints.NotBlank;

/** Change only a batch's status. */
public class BatchStatusRequest {

    @NotBlank(message = "Status is required")
    public String status;
}
