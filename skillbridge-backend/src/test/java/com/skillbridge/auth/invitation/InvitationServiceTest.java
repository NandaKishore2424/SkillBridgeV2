package com.skillbridge.auth.invitation;

import com.skillbridge.common.exception.EmailNotSentException;
import com.skillbridge.shared.mail.MailDeliveryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InvitationServiceTest {

    private final InvitationIssuer issuer = mock(InvitationIssuer.class);
    private final InvitationMailer mailer = mock(InvitationMailer.class);
    private final InvitationService service = new InvitationService(issuer, mailer);
    private final IssuedInvitation issued = new IssuedInvitation("s@example.invalid", "tmp-PASSWORD-1");

    @Test
    @DisplayName("the password is committed first, then mailed")
    void commitsThenMails() {
        when(issuer.reissue(7L, "STUDENT")).thenReturn(issued);

        service.resend(7L, "STUDENT");

        InOrder order = inOrder(issuer, mailer);
        order.verify(issuer).reissue(7L, "STUDENT");
        order.verify(mailer).send(issued);
    }

    @Test
    @DisplayName("a failed send is a 502 EMAIL_NOT_SENT, and does not echo the password")
    void failedSendIsBadGateway() {
        when(issuer.reissue(7L, "STUDENT")).thenReturn(issued);
        doThrow(new MailDeliveryException("connection refused")).when(mailer).send(any());

        assertThatThrownBy(() -> service.resend(7L, "STUDENT"))
                .isInstanceOf(EmailNotSentException.class)
                .hasFieldOrPropertyWithValue("errorCode", "EMAIL_NOT_SENT")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("tmp-PASSWORD-1"));
    }

    @Test
    @DisplayName("an issued invitation's toString redacts the password")
    void toStringRedacts() {
        assertThat(issued.toString()).contains("s@example.invalid").doesNotContain("tmp-PASSWORD-1");
    }

    @Test
    @DisplayName("the email states the lifetime in words")
    void describesTtl() {
        assertThat(InvitationMailer.describe(Duration.ofDays(7))).isEqualTo("7 days");
        assertThat(InvitationMailer.describe(Duration.ofDays(1))).isEqualTo("1 day");
        assertThat(InvitationMailer.describe(Duration.ofHours(6))).isEqualTo("6 hours");
    }
}
