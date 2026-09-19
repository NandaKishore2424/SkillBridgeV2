package com.skillbridge.shared.mail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Picks the one {@link MailGateway} named by {@code app.mail.provider}, and
 * refuses to start if that provider is not configured -- better than an
 * invitation that fails at the moment an admin sends it.
 */
@Configuration
@EnableConfigurationProperties(MailSettings.class)
public class MailConfig {

    static final String RESEND_DEFAULT_BASE_URL = "https://api.resend.com";

    @Bean
    MailGateway mailGateway(MailSettings settings, ObjectProvider<JavaMailSender> javaMailSender) {
        return switch (settings.provider()) {
            case LOG -> new LogOnlyMailGateway();
            case SMTP -> {
                JavaMailSender sender = javaMailSender.getIfAvailable();
                if (sender == null) {
                    throw new IllegalStateException(
                            "app.mail.provider=SMTP needs spring.mail.host (SPRING_MAIL_HOST); for Mailpit, localhost with port 1025");
                }
                yield new SmtpMailGateway(sender, settings.from());
            }
            case RESEND -> new ResendMailGateway(resendClient(settings).build(), settings.from());
        };
    }

    static RestClient.Builder resendClient(MailSettings settings) {
        MailSettings.Resend resend = settings.resend();
        if (resend == null || !StringUtils.hasText(resend.apiKey())) {
            throw new IllegalStateException("app.mail.provider=RESEND needs app.mail.resend.api-key (RESEND_API_KEY)");
        }
        // Bounded: an invitation is sent while an admin waits for the response.
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(5));
        timeouts.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder()
                .baseUrl(StringUtils.hasText(resend.baseUrl()) ? resend.baseUrl() : RESEND_DEFAULT_BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + resend.apiKey())
                .requestFactory(timeouts);
    }
}
