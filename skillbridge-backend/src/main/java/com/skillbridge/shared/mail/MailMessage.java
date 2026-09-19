package com.skillbridge.shared.mail;

import java.util.Objects;

/**
 * One plain-text email.
 *
 * <p>{@link #toString()} leaves the body out. Bodies here carry temporary
 * passwords, and a record's generated {@code toString} would print one into any
 * log line or exception message that mentioned the message.
 */
public record MailMessage(String to, String subject, String text) {

    public MailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
        return "MailMessage[to=" + to + ", subject=" + subject + "]";
    }
}
