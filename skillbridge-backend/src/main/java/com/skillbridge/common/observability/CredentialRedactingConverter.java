package com.skillbridge.common.observability;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Last line of defence against a credential reaching a log file.
 *
 * <p>The rule is that nothing logs a token, a password or an authorization
 * header in the first place, and {@code docs/LOGGING.md} states it. This exists
 * because the rule will eventually be broken by somebody debugging at speed, and
 * a credential in a log file is a credential that has been copied to wherever
 * logs are shipped, retained for however long logs are retained, and readable by
 * everyone who can read logs. Rotating after the fact is the only remedy, and
 * only if anybody notices.
 *
 * <p><b>A backstop, not a licence.</b> It matches the shapes credentials
 * usually take in a message — {@code password=...}, {@code "token": "..."},
 * {@code Bearer eyJ...} — and it cannot match a credential logged under a name
 * nobody thought of. Redaction failing quietly is exactly why the discipline
 * matters more than the filter.
 */
public class CredentialRedactingConverter extends MessageConverter {

    private static final String MASK = "[REDACTED]";

    /**
     * {@code key=value} and {@code "key": "value"} for credential-shaped keys.
     *
     * <p>The value stops at the first delimiter — quote, comma, ampersand,
     * whitespace, closing brace — so one secret in a longer message does not
     * blank the rest of the line and take the context with it.
     *
     * <p>The optional quote after the key matters more than it looks: without it
     * this matched {@code password=x} and missed {@code "password":"x"}, which is
     * the form a logged request body actually takes and therefore the one case
     * worth catching. Found by the test, not by reading.
     */
    private static final Pattern KEYED = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|secret|token|api[-_]?key|authorization|refresh_?token|"
                    + "access_?token|client_?secret)\\b\"?\\s*[=:]\\s*\"?([^\",&}\\s]+)\"?");

    /** A bearer token in flight, which arrives without a key beside it. */
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*");

    /** A JWT on its own: three base64url segments separated by dots. */
    private static final Pattern JWT = Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}\\b");

    @Override
    public String convert(ILoggingEvent event) {
        return redact(super.convert(event));
    }

    static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String redacted = KEYED.matcher(message).replaceAll("$1=" + MASK);
        redacted = BEARER.matcher(redacted).replaceAll("Bearer " + MASK);
        return JWT.matcher(redacted).replaceAll(MASK);
    }
}
