package com.skillbridge.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Body of POST /auth/first-login: trade the temporary password for one the user chose. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FirstLoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be valid")
    private String email;

    @NotBlank(message = "The temporary password is required")
    @Size(max = 128)
    private String temporaryPassword;

    @NotBlank(message = "A new password is required")
    @Size(max = 128)
    private String newPassword;
}
