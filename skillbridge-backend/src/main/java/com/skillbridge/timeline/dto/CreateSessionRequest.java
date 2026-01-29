package com.skillbridge.timeline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

/**
 * Request DTO for creating a timeline session
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateSessionRequest {
    @NotNull(message = "Session number is required")
    private Integer sessionNumber;

    @NotBlank(message = "Session title is required")
    private String title;

    private String description;

    private Long topicId;

    private LocalDate plannedDate;
}
