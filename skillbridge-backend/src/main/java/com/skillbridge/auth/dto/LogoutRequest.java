package com.skillbridge.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /auth/logout. Browsers send it empty: the refresh token travels
 * in the HttpOnly cookie. A non-browser client may name the token here instead.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogoutRequest {
    private String refreshToken;
}
