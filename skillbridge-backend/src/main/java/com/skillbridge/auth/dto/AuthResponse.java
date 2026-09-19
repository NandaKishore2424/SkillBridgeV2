package com.skillbridge.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    /**
     * Never serialised. The controller moves it into the HttpOnly
     * {@code skillbridge_refresh_token} cookie, the only place it travels. In a
     * JSON body any script on the page could read it, which defeats the cookie.
     */
    @JsonIgnore
    private String refreshToken;
    private Long expiresIn; // Token expiration in seconds
    private UserDto user; // User information matching frontend format
}

