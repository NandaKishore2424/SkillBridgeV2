package com.skillbridge.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of POST /auth/change-password. The strength rules are PasswordPolicy's; this only bounds the input. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "Your current password is required")
    @Size(max = 128)
    private String oldPassword;

    @NotBlank(message = "A new password is required")
    @Size(max = 128)
    private String newPassword;
}
