package com.skillbridge.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * The answer to "who am I", for a client rehydrating after a page refresh.
 *
 * <p>Deliberately richer than the {@code user} block in a login response: it
 * carries the display name and college name, which live on the student or
 * trainer profile rather than on the account, and which a client otherwise has
 * to guess at from the email local part.
 *
 * <p>Every field here is derived from the authenticated caller. Nothing is taken
 * from the request, so there is no id to get wrong and no tenant check to
 * forget.
 */
@Data
@Builder
public class CurrentUserDTO {

    private Long id;
    private String email;
    private Set<String> roles;
    private String primaryRole;

    private Long collegeId;
    private String collegeName;

    /** From the student or trainer profile; null for admins, who have neither. */
    private String fullName;

    /** The profile row's id, so the client can address profile endpoints. */
    private Long profileId;

    private Boolean profileCompleted;
    private Boolean mustChangePassword;
    private String accountStatus;
}
