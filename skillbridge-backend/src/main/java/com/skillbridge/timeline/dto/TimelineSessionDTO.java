package com.skillbridge.timeline.dto;

import lombok.*;

import java.time.LocalDate;

/**
 * DTO for a timeline session
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimelineSessionDTO {
    private Long id;
    private Integer sessionNumber;
    private String title;
    private String description;
    private Long topicId;
    private String topicName;
    private LocalDate plannedDate;
}
