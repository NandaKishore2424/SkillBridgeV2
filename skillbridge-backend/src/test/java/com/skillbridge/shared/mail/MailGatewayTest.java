package com.skillbridge.shared.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The mail gateways without a network. SMTP is covered for real against
 * Mailpit by {@code InvitationEmailTest}; Resend cannot be, because it only
 * sends from a verified domain and this project has none, so its request is
 * checked here against a mocked server.
 */
@ExtendWith(OutputCaptureExtension.class)
class MailGatewayTest {

    private static final MailMessage MESSAGE =
            new MailMessage("student@example.invalid", "Your SkillBridge account", "Temporary password: s3cret-VALUE");

    private static MailSettings resendSettings(String apiKey) {
        return new MailSettings(MailSettings.Provider.RESEND, "SkillBridge <no-reply@example.invalid>",
                "http://localhost:5173/first-login", new MailSettings.Resend(apiKey, "https://resend.test"));
    }

    @Test
    @DisplayName("Resend: POST /emails with the bearer key and the message as JSON")
    void resendRequestShape() {
        RestClient.Builder builder = MailConfig.resendClient(resendSettings("re_test_key"));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://resend.test/emails"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer re_test_key"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value("SkillBridge <no-reply@example.invalid>"))
                .andExpect(jsonPath("$.to[0]").value("student@example.invalid"))
                .andExpect(jsonPath("$.subject").value("Your SkillBridge account"))
                .andExpect(jsonPath("$.text").value("Temporary password: s3cret-VALUE"))
                .andRespond(withSuccess("{\"id\":\"4ef9a417\"}", MediaType.APPLICATION_JSON));

        new ResendMailGateway(builder.build(), "SkillBridge <no-reply@example.invalid>").send(MESSAGE);

        server.verify();
    }

    @Test
    @DisplayName("Resend: a refusal becomes MailDeliveryException, keeping Resend's reason")
    void resendRefusal() {
        RestClient.Builder builder = MailConfig.resendClient(resendSettings("re_test_key"));
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://resend.test/emails"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"statusCode\":403,\"message\":\"The example.invalid domain is not verified.\"}"));

        assertThatThrownBy(() -> new ResendMailGateway(builder.build(), "x@example.invalid").send(MESSAGE))
                .isInstanceOf(MailDeliveryException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("not verified");
    }

    @Test
    @DisplayName("Resend without an API key fails at startup")
    void resendNeedsKey() {
        assertThatThrownBy(() -> MailConfig.resendClient(resendSettings(" ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RESEND_API_KEY");
    }

    @Test
    @DisplayName("SMTP without spring.mail.host fails at startup, naming the setting")
    void smtpNeedsHost() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(MailConfig.class)
                .withPropertyValues("app.mail.provider=SMTP", "app.mail.from=x@example.invalid",
                        "app.mail.login-url=http://localhost")
                .run(context -> assertThat(context).getFailure()
                        .rootCause().hasMessageContaining("SPRING_MAIL_HOST"));
    }

    @Test
    @DisplayName("SMTP with a host gets the SMTP gateway")
    void smtpWithHost() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(MailConfig.class)
                .withPropertyValues("app.mail.provider=SMTP", "app.mail.from=x@example.invalid",
                        "app.mail.login-url=http://localhost", "spring.mail.host=localhost")
                .run(context -> assertThat(context.getBean(MailGateway.class)).isInstanceOf(SmtpMailGateway.class));
    }

    @Test
    @DisplayName("LOG records recipient and subject, never the body")
    void logOnlyKeepsBodyOut(CapturedOutput output) {
        new LogOnlyMailGateway().send(MESSAGE);

        assertThat(output.getAll()).contains("student@example.invalid").doesNotContain("s3cret-VALUE");
    }

    @Test
    @DisplayName("a message's toString leaves the body out")
    void messageToStringOmitsBody() {
        assertThat(MESSAGE.toString()).contains("student@example.invalid").doesNotContain("s3cret-VALUE");
    }
}
