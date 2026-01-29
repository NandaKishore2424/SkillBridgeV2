package com.skillbridge.timeline.dto;

import lombok.*;

import java.time.LocalDate;

/**
 * Request DTO for updating a timeline session
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateSessionRequest {
    private Integer sessionNumber;
    private String title;
    private String description;
    private Long topicId;
    private LocalDate plannedDate;
}
