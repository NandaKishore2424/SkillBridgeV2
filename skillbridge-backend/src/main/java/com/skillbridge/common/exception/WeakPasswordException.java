package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A chosen password that breaks {@link com.skillbridge.auth.security.PasswordPolicy}.
 * 400 with code {@code WEAK_PASSWORD}; each broken rule is listed under
 * {@code details} as {@code rule1}, {@code rule2}, ... so the client can show
 * them all at once.
 */
public class WeakPasswordException extends ApiException {

    public WeakPasswordException(List<String> problems) {
        super(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", "The new password does not meet the password policy.",
                numbered(problems));
    }

    private static Map<String, String> numbered(List<String> problems) {
        Map<String, String> details = new LinkedHashMap<>();
        for (int i = 0; i < problems.size(); i++) {
            details.put("rule" + (i + 1), problems.get(i));
        }
        return details;
    }
}
