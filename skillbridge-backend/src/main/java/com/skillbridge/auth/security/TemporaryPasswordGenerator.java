package com.skillbridge.auth.security;

import java.security.SecureRandom;

/**
 * Generates the one-time password a newly provisioned account is created with.
 *
 * <p>Bulk upload previously used the account's own email address as its
 * temporary password ({@code String temporaryPassword = dto.getEmail()}). An
 * email address is not a secret: it is listed in {@code GET /admin/students},
 * visible to every college admin, printed on class lists, and usually derivable
 * from a person's name. Every account created that way could be signed into by
 * anyone who knew who its owner was — which was verified against the live
 * database before this class was written.
 *
 * <p>{@link SecureRandom} rather than {@code Math.random()} or {@code Random}:
 * the latter are seeded predictably enough that observing a few outputs reveals
 * the rest of the sequence, and a bulk upload generates a long sequence in a
 * single run.
 *
 * <p>The alphabet omits characters that are easy to confuse when a password is
 * read off a screen and typed by hand — {@code 0/O}, {@code 1/l/I} — because
 * that is exactly how these are delivered today.
 */
public final class TemporaryPasswordGenerator {

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ" + "abcdefghijkmnopqrstuvwxyz" + "23456789";

    /** 14 characters over a 57-character alphabet is about 81.7 bits. */
    private static final int LENGTH = 14;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswordGenerator() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
