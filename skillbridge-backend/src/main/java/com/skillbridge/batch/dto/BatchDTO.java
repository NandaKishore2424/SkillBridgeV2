package com.skillbridge.batch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchDTO {
    private Long id;
    private Long collegeId;
    private String collegeName;
    private String name;
    private String description;
    private String status;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Counts for UI display
    private int trainerCount;
    private int companyCount;
    private int studentCount;

    /**
     * Maps a batch whose {@code college} is already fetch-joined.
     *
     * <p>Touches nothing lazy: the college must be loaded and all three counts
     * are supplied by the caller, so this cannot throw
     * {@code LazyInitializationException} however it is reached. The counts come
     * from one grouped query per page rather than per row -- the collections
     * are deliberately not fetch-joined, because Hibernate cannot paginate a
     * collection fetch in SQL and would silently do it in memory.
     *
     * <p>Lives on the DTO rather than in a controller because two controllers
     * now need it: the college admin's own batch list and the SYSTEM_ADMIN's
     * per-college one.
     */
    public static BatchDTO from(com.skillbridge.batch.entity.Batch batch,
                                long trainerCount, long companyCount, long studentCount) {
        return BatchDTO.builder()
                .id(batch.getId())
                .collegeId(batch.getCollege().getId())
                .collegeName(batch.getCollege().getName())
                .name(batch.getName())
                .description(batch.getDescription())
                .status(batch.getStatus())
                .startDate(batch.getStartDate())
                .endDate(batch.getEndDate())
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .trainerCount((int) trainerCount)
                .companyCount((int) companyCount)
                .studentCount((int) studentCount)
                .build();
    }
}
