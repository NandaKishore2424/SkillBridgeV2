package com.skillbridge.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private Long id;
    private String email;
    private String role; // Single role (primary role)
    private Long collegeId;
    private Boolean isActive;
    private Boolean mustChangePassword;
    private String accountStatus;
    private Boolean profileCompleted;
}
