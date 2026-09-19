package com.skillbridge.auth.invitation;

/**
 * A temporary password that has just been set on an account and still has to
 * be mailed. Lives only in memory: the password is never persisted in the
 * clear, so it cannot go through the outbox.
 *
 * <p>{@link #toString()} masks the password.
 */
public record IssuedInvitation(String email, String temporaryPassword) {

    @Override
    public String toString() {
        return "IssuedInvitation[email=" + email + ", temporaryPassword=<redacted>]";
    }
}
