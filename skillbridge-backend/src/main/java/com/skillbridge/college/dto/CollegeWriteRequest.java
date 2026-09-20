package com.skillbridge.college.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a system admin may set on a college.
 *
 * <p>A DTO rather than the {@code College} entity, which is what the two write
 * endpoints used to bind. Binding an entity from a request body hands the
 * client every column on it — {@code id}, {@code createdAt}, {@code deletedAt},
 * {@code deletedBy} — and the fields a soft delete depends on are not fields a
 * client should be able to set.
 *
 * <p>Both records are deliberately separate. Creating needs a name and a code;
 * updating must not, because the edit form sends only what changed.
 */
public final class CollegeWriteRequest {

    private CollegeWriteRequest() {
    }

    /** Everything a new college needs. */
    public record Create(
            @NotBlank(message = "Name is required")
            @Size(max = 255, message = "Name must be at most 255 characters")
            String name,

            @NotBlank(message = "Code is required")
            @Size(max = 50, message = "Code must be at most 50 characters")
            String code,

            @Email(message = "Email must be a valid address")
            @Size(max = 255, message = "Email must be at most 255 characters")
            String email,

            @Size(max = 20, message = "Phone must be at most 20 characters")
            String phone,

            String address) {
    }

    /**
     * A change to an existing college. Every field is optional; a null one means
     * "leave it alone", not "set it to null".
     *
     * <p>That distinction is the whole point. The previous handler bound the
     * entity, set its id and saved it — a full replace — so a body of
     * {@code {"name": "..."}} nulled the code, which is NOT NULL, and the edit
     * form answered <b>409 CONSTRAINT_VIOLATION</b>: "This operation conflicts
     * with existing data. The record may already exist." Renaming a college
     * from the UI was impossible, and the message pointed the admin at a
     * duplicate that did not exist.
     */
    public record Update(
            @Size(max = 255, message = "Name must be at most 255 characters")
            String name,

            @Size(max = 50, message = "Code must be at most 50 characters")
            String code,

            @Email(message = "Email must be a valid address")
            @Size(max = 255, message = "Email must be at most 255 characters")
            String email,

            @Size(max = 20, message = "Phone must be at most 20 characters")
            String phone,

            String address) {
    }
}
