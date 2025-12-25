package com.skillbridge.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String accessToken; // For now, we'll use a simple token. JWT can be added later
    private String refreshToken;
    private Long expiresIn; // Token expiration in seconds
    private UserDto user; // User information matching frontend format
}

