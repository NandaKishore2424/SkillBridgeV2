package com.skillbridge.college.dto;

import com.skillbridge.college.entity.College;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Wire format for a college.
 *
 * <p>{@code CollegeController} returned the {@code College} entity itself from
 * five endpoints, and {@code PublicCollegeController} — which is
 * <b>unauthenticated</b> — returned a list of them. Every column was therefore
 * public API, which stopped being merely untidy when soft delete added
 * {@code deleted_at} and {@code deleted_by}: internal bookkeeping, and
 * {@code deleted_by} is a user id, both of which began appearing in responses
 * the moment that change landed.
 *
 * <p>The fields here are exactly what the React {@code College} type reads, plus
 * the timestamps the college detail screen shows. Nothing else.
 */
@Data
@Builder
public class CollegeDTO {

    private Long id;
    private String name;
    private String code;
    private String email;
    private String phone;
    private String address;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CollegeDTO from(College college) {
        return CollegeDTO.builder()
                .id(college.getId())
                .name(college.getName())
                .code(college.getCode())
                .email(college.getEmail())
                .phone(college.getPhone())
                .address(college.getAddress())
                .status(college.getStatus())
                .createdAt(college.getCreatedAt())
                .updatedAt(college.getUpdatedAt())
                .build();
    }
}
