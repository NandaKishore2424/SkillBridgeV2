package com.skillbridge.auth.invitation;

import com.skillbridge.auth.AuthProperties;

import com.skillbridge.shared.mail.MailGateway;
import com.skillbridge.shared.mail.MailMessage;
import com.skillbridge.shared.mail.MailSettings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Turns an {@link IssuedInvitation} into an email.
 *
 * <p>Call it only after the transaction that set the password has committed.
 * Sent earlier, the email can carry a password that a rollback then discards;
 * and a send inside the transaction holds a database connection for the SMTP
 * round trip ({@code ConnectionHoldingRulesTest}).
 */
@Component
public class InvitationMailer {

    static final String SUBJECT = "Your SkillBridge account";

    private final MailGateway gateway;
    private final String loginUrl;
    private final Duration ttl;

    public InvitationMailer(MailGateway gateway, MailSettings settings, AuthProperties auth) {
        this.gateway = gateway;
        this.loginUrl = settings.loginUrl();
        this.ttl = auth.invitationTtl();
    }

    /** @throws com.skillbridge.shared.mail.MailDeliveryException if it was not sent */
    public void send(IssuedInvitation invitation) {
        gateway.send(new MailMessage(invitation.email(), SUBJECT, """
                Hello,

                A SkillBridge account has been created for you.

                  Email:              %s
                  Temporary password: %s

                Set your own password at %s
                The temporary password works once, for %s. After that, ask your
                college admin to send a new invitation.
                """.formatted(invitation.email(), invitation.temporaryPassword(), loginUrl, describe(ttl))));
    }

    static String describe(Duration ttl) {
        return ttl.toDays() >= 1 ? plural(ttl.toDays(), "day") : plural(Math.max(1, ttl.toHours()), "hour");
    }

    private static String plural(long n, String unit) {
        return n + " " + unit + (n == 1 ? "" : "s");
    }
}
