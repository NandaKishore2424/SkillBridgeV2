package com.skillbridge.shared.mail;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.mail.*}.
 *
 * @param provider      which {@link MailGateway} is active
 * @param from          the From address
 * @param loginUrl      where an invitation sends people to set their password
 * @param resend        settings for {@link Provider#RESEND} only
 */
@Validated
@ConfigurationProperties("app.mail")
public record MailSettings(
        @NotNull Provider provider,
        @NotBlank String from,
        @NotBlank String loginUrl,
        Resend resend) {

    public enum Provider {
        /** Records recipient and subject only; sends nothing. The default, so the app starts anywhere. */
        LOG,
        /** SMTP via {@code spring.mail.*}: Mailpit locally and for the demo. */
        SMTP,
        /** Resend's HTTPS API. Needs a domain verified with Resend. */
        RESEND
    }

    public record Resend(String apiKey, String baseUrl) {
    }
}
