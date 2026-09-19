package com.skillbridge.shared.mail;

/**
 * Sends email. One implementation is active, chosen by {@code app.mail.provider}.
 *
 * <p>Every implementation is a network call (SMTP or HTTPS), so this must never
 * be called inside a transaction: a connection would be held for the whole
 * round trip. {@code ConnectionHoldingRulesTest} names this interface for that
 * reason; its call-graph walk cannot see through an interface to the
 * implementation behind it.
 */
public interface MailGateway {

    /**
     * @throws MailDeliveryException if the message was not accepted for delivery
     */
    void send(MailMessage message);
}
