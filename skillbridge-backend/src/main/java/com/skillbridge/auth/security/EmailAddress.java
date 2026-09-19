package com.skillbridge.auth.security;

import java.util.Locale;

/**
 * Emails are stored and compared lower-cased and trimmed.
 *
 * <p>{@code users.email} is unique, and PostgreSQL compares it exactly, so
 * without this "Asha@X.edu" and "asha@x.edu" are two accounts, and whoever
 * typed the other one cannot log in. {@code User} normalises before every
 * write, every lookup goes through here, and V6's CHECK constraint refuses a
 * row that slipped past both.
 */
public final class EmailAddress {

    private EmailAddress() {
    }

    public static String normalise(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
