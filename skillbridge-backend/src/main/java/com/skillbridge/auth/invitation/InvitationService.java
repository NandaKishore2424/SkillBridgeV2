package com.skillbridge.auth.invitation;

import com.skillbridge.common.exception.EmailNotSentException;
import com.skillbridge.shared.mail.MailDeliveryException;
import org.springframework.stereotype.Service;

/**
 * Resends an invitation: commit the new password, then mail it.
 *
 * <p>Deliberately not {@code @Transactional}. The password change commits
 * inside {@link InvitationIssuer#reissue} and only then is the email sent, so a
 * rollback can never leave someone holding a password that was discarded, and
 * no connection is held across the SMTP call.
 *
 * <p>The cost is at-most-once delivery. If the send fails, the account has a
 * password nobody received; the admin sees 502 {@code EMAIL_NOT_SENT} and
 * resends, which issues another. The alternative -- queueing the send through
 * the outbox for retries -- would mean storing the temporary password in the
 * clear until it went out.
 */
@Service
public class InvitationService {

    private final InvitationIssuer issuer;
    private final InvitationMailer mailer;

    public InvitationService(InvitationIssuer issuer, InvitationMailer mailer) {
        this.issuer = issuer;
        this.mailer = mailer;
    }

    public void resend(Long userId, String expectedRole) {
        IssuedInvitation invitation = issuer.reissue(userId, expectedRole);
        try {
            mailer.send(invitation);
        } catch (MailDeliveryException e) {
            throw new EmailNotSentException(
                    "Invitation for user " + userId + " was reissued but not sent: " + e.getMessage(), e);
        }
    }
}
