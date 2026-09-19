package com.skillbridge.auth.security;

import com.skillbridge.common.exception.WeakPasswordException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a password chosen by a person must satisfy. Applied everywhere a
 * human-chosen password is set: first login, password change, and an admin
 * creating an account with a password. Generated temporary passwords
 * ({@link TemporaryPasswordGenerator}) are random, so they are not checked.
 *
 * <p><b>Rules, and why these ones.</b> NIST SP 800-63B favours length and a
 * blocklist over composition rules ("one digit, one symbol"), which push people
 * to {@code Password1!} and add little entropy. Revision 4 sets 15 characters as
 * the minimum for a password that is the only factor, and this application has
 * no second factor. So:
 * <ul>
 *   <li>at least {@value #MIN_LENGTH} characters and at most {@value #MAX_LENGTH}
 *       (the upper bound only caps the work BCrypt is asked to do; BCrypt reads
 *       at most 72 bytes anyway);</li>
 *   <li>not built from the account's own email address or the product's name;</li>
 *   <li>not one character, or one short chunk, repeated
 *       ({@code aaaaaaaaaaaaaaa}, {@code abcabcabcabcabc});</li>
 *   <li>not on a short list of passwords that attackers try first.</li>
 * </ul>
 * All violations are reported together, so a user fixes the password once.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 15;
    public static final int MAX_LENGTH = 128;

    private static final Set<String> CONTEXT_WORDS = Set.of("skillbridge");

    /**
     * Long passwords attackers try first. Short ones are already refused by the
     * length rule, so only phrases of 15 or more characters belong here. Matched
     * case-insensitively, and with spaces removed.
     */
    private static final Set<String> COMMON = Set.of(
            "passwordpassword", "password1234567", "password12345678", "123456789012345",
            "1234567890123456", "qwertyuiopasdfgh", "qwertyuiopasdfghjkl", "iloveyouiloveyou",
            "letmeinletmein", "welcome12345678", "adminadminadmin", "changemechangeme",
            "correcthorsebatterystaple", "trustno1trustno1", "passw0rdpassw0rd");

    private PasswordPolicy() {
    }

    /** Throws {@link WeakPasswordException}, listing every rule broken, unless {@code password} is acceptable. */
    public static void check(String password, String email) {
        List<String> problems = violations(password, email);
        if (!problems.isEmpty()) {
            throw new WeakPasswordException(problems);
        }
    }

    public static List<String> violations(String password, String email) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.isEmpty()) {
            problems.add("A password is required.");
            return problems;
        }
        if (password.length() < MIN_LENGTH) {
            problems.add("Use at least " + MIN_LENGTH + " characters. A phrase of a few words is easiest to remember.");
        }
        if (password.length() > MAX_LENGTH) {
            problems.add("Use at most " + MAX_LENGTH + " characters.");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        String localPart = email == null ? "" : email.toLowerCase(Locale.ROOT).split("@", 2)[0];
        if (localPart.length() >= 4 && lower.contains(localPart)) {
            problems.add("Do not build the password from your email address.");
        }
        for (String word : CONTEXT_WORDS) {
            if (lower.contains(word)) {
                problems.add("Do not build the password from the name of this site.");
            }
        }
        if (isRepetition(password)) {
            problems.add("Do not repeat one character or a short pattern.");
        }
        if (COMMON.contains(lower.replace(" ", ""))) {
            problems.add("This password is on the list attackers try first. Choose another.");
        }
        return problems;
    }

    /** True if the whole password is one chunk of up to 4 characters repeated. */
    private static boolean isRepetition(String password) {
        for (int size = 1; size <= 4 && size < password.length(); size++) {
            if (password.length() % size != 0) {
                continue;
            }
            String chunk = password.substring(0, size);
            if (chunk.repeat(password.length() / size).equals(password)) {
                return true;
            }
        }
        return false;
    }
}
