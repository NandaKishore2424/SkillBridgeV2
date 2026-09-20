package com.skillbridge.auth.invitation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.AuthService;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.common.exception.UnauthorizedException;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TestAuthentication;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An invitation, end to end: the admin resends it, a real SMTP server (Mailpit,
 * the same image docker-compose runs) receives it, and the password in the
 * email is then used to complete first login.
 *
 * <p>That last step is the point. Each half could pass on its own -- a mail
 * arrives, a password is set -- while the password mailed is not the one stored.
 * Testing the crossing is what catches that (START-HERE, trap 5).
 */
@SpringBootTest(properties = {"app.mail.provider=SMTP", "app.auth.invitation-ttl=7d"})
@AutoConfigureMockMvc
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class InvitationEmailTest {

    private static final String COLLEGE_CODE = "INVITEMAIL";
    private static final Pattern TEMPORARY_PASSWORD = Pattern.compile("Temporary password: (\\S+)");
    private static final String NEW_PASSWORD = "correct horse battery staple 42";

    @SuppressWarnings("resource") // Ryuk removes it when the JVM exits, like the PostgreSQL container.
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>("axllent/mailpit:v1.27")
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025));

    static {
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void smtp(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthService authService;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newHttpClient();
    private TenantFixture fixture;
    private Long studentUserId;
    private String studentEmail;

    @BeforeEach
    void seed() throws Exception {
        mailpit("DELETE", "/api/v1/messages");
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(1, 1);
        studentUserId = fixture.studentUserIds.get(0);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'STUDENT' "
                + "ON CONFLICT DO NOTHING", studentUserId);
        jdbc.update("UPDATE users SET account_status = 'PENDING_SETUP' WHERE id = ?", studentUserId);
        studentEmail = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, studentUserId);
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
    }

    @Test
    @DisplayName("the resent invitation arrives by SMTP, its password completes first login, and the log never shows it")
    void invitationArrivesAndItsPasswordWorks(CapturedOutput output) throws Exception {
        resend();

        String password = temporaryPasswordMailedTo(studentEmail);

        assertThat(output.getAll())
                .as("the temporary password must not reach any log line")
                .doesNotContain(password);
        assertThatNoException().isThrownBy(() -> authService.firstLogin(studentEmail, password, NEW_PASSWORD));
    }

    @Test
    @DisplayName("an invitation older than app.auth.invitation-ttl no longer works, at login or at first login")
    void expiredInvitationIsRefused() throws Exception {
        resend();
        String password = temporaryPasswordMailedTo(studentEmail);
        jdbc.update("UPDATE users SET invitation_sent_at = now() - interval '8 days' WHERE id = ?", studentUserId);

        assertThatThrownBy(() -> authService.firstLogin(studentEmail, password, NEW_PASSWORD))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("expired");
        assertThatThrownBy(() -> authService.login(new LoginRequest(studentEmail, password)))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("expired");
    }

    private void resend() throws Exception {
        User admin = userRepository.findById(fixture.adminUserId).orElseThrow();
        mvc.perform(post("/api/v1/admin/students/{id}/resend-invitation", studentUserId)
                        .header("Authorization", TestAuthentication.bearer(jwtService, admin, "COLLEGE_ADMIN")))
                .andExpect(status().isOk());
    }

    /** The one message Mailpit holds for {@code to}, and the password in its body. */
    private String temporaryPasswordMailedTo(String to) throws Exception {
        JsonNode messages = mailpit("GET", "/api/v1/messages").path("messages");
        assertThat(messages).as("exactly one message in Mailpit").hasSize(1);
        JsonNode message = messages.get(0);
        assertThat(message.path("To").get(0).path("Address").asText()).isEqualTo(to);
        assertThat(message.path("Subject").asText()).isEqualTo(InvitationMailer.SUBJECT);

        String body = mailpit("GET", "/api/v1/message/" + message.path("ID").asText()).path("Text").asText();
        Matcher m = TEMPORARY_PASSWORD.matcher(body);
        assertThat(m.find()).as("the body carries a temporary password:%n%s", body).isTrue();
        return m.group(1);
    }

    private JsonNode mailpit(String method, String path) throws Exception {
        URI uri = URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + path);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isBetween(200, 299);
        // DELETE answers a plain "ok", not JSON.
        return "GET".equals(method) ? objectMapper.readTree(response.body()) : objectMapper.createObjectNode();
    }
}
