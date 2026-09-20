package com.skillbridge.security;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.shared.mail.MailGateway;
import com.skillbridge.shared.mail.MailMessage;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import com.skillbridge.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * No credential this application mints ends up in its own log.
 *
 * <p>A log is copied, shipped, and read by more people than a database is. The
 * project has been here before: {@code EmailService} wrote every temporary
 * password in plain text, and {@code User.toString()} would have printed
 * password hashes into any line that mentioned a user.
 *
 * <p>Rather than grepping for likely-looking strings, each test makes the
 * application produce a real secret, takes the exact value, and asserts it does
 * not appear anywhere in the output of that request. What is checked is what
 * was actually issued.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SecretsStayOutOfLogsTest {

    private static final String PASSWORD = "correct horse battery staple 42";
    private static final Pattern TEMPORARY_PASSWORD = Pattern.compile("Temporary password: (\\S+)");
    private static final String HEADER = "Full Name,Email,Roll Number,Degree,Branch,Year\n";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockitoBean private MailGateway mail;

    private TenantFixture fixture;
    private String email;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, "SECRETLOG");
        fixture.seed(0, 0);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?",
                passwordEncoder.encode(PASSWORD), fixture.adminUserId);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'COLLEGE_ADMIN' "
                + "ON CONFLICT DO NOTHING", fixture.adminUserId);
        email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, fixture.adminUserId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM bulk_uploads WHERE college_id = ?", fixture.collegeId);
        fixture.remove();
    }

    @Test
    @DisplayName("a login logs neither the access token it issues nor the refresh cookie")
    void loginKeepsItsTokensOutOfTheLog(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        String accessToken = between(body, "\"accessToken\":\"", "\"");
        String refreshCookie = result.getResponse().getCookie("skillbridge_refresh_token") == null
                ? null : result.getResponse().getCookie("skillbridge_refresh_token").getValue();

        assertThat(accessToken).as("the login must have issued one").isNotBlank();
        assertThat(refreshCookie).as("and set the refresh cookie").isNotBlank();
        assertThat(output.getAll())
                .as("the access token must not be in the log")
                .doesNotContain(accessToken);
        assertThat(output.getAll())
                .as("nor the refresh token")
                .doesNotContain(refreshCookie);
        assertThat(output.getAll())
                .as("nor the password that was sent")
                .doesNotContain(PASSWORD);
    }

    @Test
    @DisplayName("a CSV import logs none of the temporary passwords it generates")
    void importKeepsItsPasswordsOutOfTheLog(CapturedOutput output) throws Exception {
        String csv = HEADER
                + "Asha Verma,asha@secretlog.test,SL-001,B.Tech,CSE,3\n"
                + "Ravi Kumar,ravi@secretlog.test,SL-002,B.Tech,CSE,2\n";

        long uploadId = Long.parseLong(between(mvc.perform(multipart("/api/v1/admin/students/bulk-upload")
                        .file(new MockMultipartFile("file", "students.csv", "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .header("Authorization", adminToken()))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString(), "\"uploadId\":", ","));
        awaitDone(uploadId);

        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(mail, atLeastOnce()).send(sent.capture());
        List<String> passwords = sent.getAllValues().stream()
                .map(message -> {
                    Matcher matcher = TEMPORARY_PASSWORD.matcher(message.text());
                    assertThat(matcher.find()).as("the invitation carries a password").isTrue();
                    return matcher.group(1);
                })
                .toList();

        assertThat(passwords).as("one per imported row").hasSize(2);
        for (String password : passwords) {
            assertThat(output.getAll())
                    .as("a temporary password reached the log; EmailService used to do exactly this")
                    .doesNotContain(password);
        }
    }

    private String adminToken() {
        User admin = userRepository.findById(fixture.adminUserId).orElseThrow();
        return TestAuthentication.bearer(jwtService, admin, "COLLEGE_ADMIN");
    }

    private void awaitDone(long uploadId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            if (!"PROCESSING".equals(jdbc.queryForObject(
                    "SELECT status FROM bulk_uploads WHERE id = ?", String.class, uploadId))) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("upload " + uploadId + " never finished");
    }

    private static String between(String text, String after, String before) {
        int start = text.indexOf(after);
        assertThat(start).as("%s in %s", after, text).isNotNegative();
        start += after.length();
        return text.substring(start, text.indexOf(before, start));
    }
}
