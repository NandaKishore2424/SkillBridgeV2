package com.skillbridge.bulkupload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.bulkupload.importer.StaleUploadSweeper;
import com.skillbridge.shared.mail.MailDeliveryException;
import com.skillbridge.shared.mail.MailGateway;
import com.skillbridge.shared.mail.MailMessage;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TestAuthentication;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The CSV import through its HTTP surface, on a real PostgreSQL.
 *
 * <p>Each test names one property the 2026-09-17 importer did not have. The
 * mail gateway is a mock so a test can make one recipient's invitation fail;
 * the real SMTP path is {@code InvitationEmailTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.import.max-rows=20")
@AutoConfigureMockMvc
@IntegrationTest
class CsvImportTest {

    private static final String COLLEGE_CODE = "CSVIMPORT";
    private static final String HEADER = "Full Name,Email,Roll Number,Degree,Branch,Year\n";

    @LocalServerPort private int port;
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StaleUploadSweeper sweeper;
    @MockitoBean private MailGateway mail;

    private TenantFixture fixture;
    private String token;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(1, 1);
        token = TestAuthentication.bearer(jwtService,
                userRepository.findById(fixture.adminUserId).orElseThrow(), "COLLEGE_ADMIN");
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS csv_import_test_explode ON students");
        jdbc.execute("DROP FUNCTION IF EXISTS csv_import_test_explode()");
        jdbc.update("DELETE FROM bulk_uploads WHERE college_id = ?", fixture.collegeId);
        fixture.remove();
    }

    @Test
    @DisplayName("each bad row fails alone with a reason the admin can act on; good rows import and are mailed")
    void rowsFailIndividually() throws Exception {
        String existing = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class,
                fixture.studentUserIds.get(0));
        long uploadId = uploadAndWait(HEADER
                + "Asha Verma,Asha@CSVImport.test,R-001,B.Tech,CSE,3\n"      // 2: good, mixed-case email
                + "No Email,not-an-email,R-002,,,\n"                         // 3: invalid email
                + "Asha Again,asha@csvimport.test,R-003,,,\n"                // 4: same email as row 2
                + "Existing,\"" + existing + "\",R-004,,,\n"                 // 5: account already exists
                + "Bad Year,year@csvimport.test,R-005,,,three\n"             // 6: year not a number
                + "Ravi Kumar,ravi@csvimport.test,R-006,,,2\n");             // 7: good

        mvc.perform(auth(get("/api/v1/admin/bulk-uploads/{id}", uploadId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalRows").value(6))
                .andExpect(jsonPath("$.successfulRows").value(2))
                .andExpect(jsonPath("$.failedRows").value(4));

        JsonNode failed = json(mvc.perform(auth(get("/api/v1/admin/bulk-uploads/{id}/rows", uploadId))));
        assertThat(failed.path("items")).extracting(r -> r.path("rowNumber").asInt()).containsExactly(3, 4, 5, 6);
        assertThat(failed.path("items")).extracting(r -> r.path("message").asText()).containsExactly(
                "Email is not a valid address",
                "An account with this email already exists",
                "An account with this email already exists",
                "Year must be a whole number, like 3; found \"three\"");
        assertThat(failed.path("items").get(0).path("values").path("Full Name").asText()).isEqualTo("No Email");

        assertThat(jdbc.queryForList("SELECT email FROM users WHERE email LIKE '%@csvimport.test' ORDER BY email",
                String.class)).containsExactly("asha@csvimport.test", "ravi@csvimport.test");
        ArgumentCaptor<MailMessage> sent = ArgumentCaptor.forClass(MailMessage.class);
        verify(mail, atLeastOnce()).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(MailMessage::to)
                .containsExactly("asha@csvimport.test", "ravi@csvimport.test");
    }

    @Test
    @DisplayName("a row that fails after its user was inserted leaves no user behind")
    void rowIsAtomic() throws Exception {
        // Fails the student INSERT, which runs after the user INSERT in the same
        // row. Without a transaction per row that user would survive as an orphan,
        // as it did on 2026-09-17.
        jdbc.execute("""
                CREATE FUNCTION csv_import_test_explode() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.full_name = 'Explode' THEN RAISE EXCEPTION 'test trigger'; END IF;
                    RETURN NEW;
                END $$""");
        jdbc.execute("CREATE TRIGGER csv_import_test_explode BEFORE INSERT ON students "
                + "FOR EACH ROW EXECUTE FUNCTION csv_import_test_explode()");

        long uploadId = uploadAndWait(HEADER
                + "Explode,orphan@csvimport.test,R-100,,,\n"
                + "Fine,fine@csvimport.test,R-101,,,\n");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'orphan@csvimport.test'",
                Integer.class)).as("the failed row's user").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = 'fine@csvimport.test'",
                Integer.class)).as("the next row still imports").isOne();
        JsonNode failed = json(mvc.perform(auth(get("/api/v1/admin/bulk-uploads/{id}/rows", uploadId))));
        assertThat(failed.path("items").get(0).path("message").asText())
                .as("an unexpected failure shows a reference, not the database's message")
                .startsWith("This row could not be imported (reference ")
                .doesNotContain("test trigger");
    }

    @Test
    @DisplayName("an invitation that fails to send leaves the account, marked EMAIL_FAILED, and resend works")
    void emailFailureIsVisibleAndRecoverable() throws Exception {
        doThrow(new MailDeliveryException("connection refused"))
                .when(mail).send(argThat(m -> m != null && m.to().equals("nomail@csvimport.test")));

        long uploadId = uploadAndWait(HEADER + "No Mail,nomail@csvimport.test,R-200,,,\n");

        mvc.perform(auth(get("/api/v1/admin/bulk-uploads/{id}", uploadId)))
                .andExpect(jsonPath("$.successfulRows").value(1))
                .andExpect(jsonPath("$.emailFailedRows").value(1));
        JsonNode rows = json(mvc.perform(auth(get("/api/v1/admin/bulk-uploads/{id}/rows", uploadId))));
        JsonNode row = rows.path("items").get(0);
        assertThat(row.path("status").asText()).isEqualTo("EMAIL_FAILED");
        long userId = row.path("userId").asLong();
        assertThat(userId).isEqualTo(jdbc.queryForObject(
                "SELECT id FROM users WHERE email = 'nomail@csvimport.test'", Long.class));

        // The provider recovers; the admin resends from the row.
        org.mockito.Mockito.reset(mail);
        mvc.perform(auth(post("/api/v1/admin/students/{id}/resend-invitation", userId)))
                .andExpect(status().isOk());
        verify(mail).send(argThat(m -> m.to().equals("nomail@csvimport.test")));
    }

    @Test
    @DisplayName("the same file uploaded twice is imported once; the second answer points at the first")
    void sameFileTwice() throws Exception {
        byte[] file = (HEADER + "Once Only,once@csvimport.test,R-300,,,\n").getBytes(StandardCharsets.UTF_8);

        JsonNode first = json(upload(file).andExpect(status().isAccepted()));
        awaitDone(first.path("uploadId").asLong());
        JsonNode second = json(upload(file).andExpect(status().isOk()));

        assertThat(second.path("alreadyUploaded").asBoolean()).isTrue();
        assertThat(second.path("uploadId").asLong()).isEqualTo(first.path("uploadId").asLong());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bulk_uploads WHERE college_id = ?",
                Integer.class, fixture.collegeId)).isOne();
    }

    @Test
    @DisplayName("a file that cannot be imported is refused in the request, with the reason")
    void fileErrorsAreImmediate() throws Exception {
        upload((HEADER.replace("Branch", "Branh") + "A,a@csvimport.test,R,,,\n").getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Unknown column(s): Branh")));
        upload((HEADER + "A,a@csvimport.test,R,,,\n".repeat(21)).getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("more than 20 rows")));
        upload((HEADER + "Zoë,z@csvimport.test,R,,,\n").getBytes(StandardCharsets.ISO_8859_1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not UTF-8")));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bulk_uploads WHERE college_id = ?",
                Integer.class, fixture.collegeId)).as("nothing recorded for a refused file").isZero();
    }

    @Test
    @DisplayName("a file over the size limit is 413 with a message, not 500")
    void tooLarge() throws Exception {
        // Through the real servlet container: MockMvc does not apply the multipart limit.
        byte[] body = multipartBody("x".repeat(1_100_000));
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/admin/students/bulk-upload"))
                        .header("Authorization", token)
                        .header("Content-Type", "multipart/form-data; boundary=csvimporttest")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(objectMapper.readTree(response.body()).path("error").asText()).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    @DisplayName("an upload whose importer stopped beating is failed; a live one is left alone")
    void staleUploadsAreFailed() {
        Long stale = insertProcessingUpload("now() - interval '11 minutes'");
        Long live = insertProcessingUpload("now()");

        sweeper.sweepNow();

        assertThat(jdbc.queryForObject("SELECT status FROM bulk_uploads WHERE id = ?", String.class, stale))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM bulk_uploads WHERE id = ?", String.class, live))
                .isEqualTo("PROCESSING");
    }

    // ------------------------------------------------------------------

    private Long insertProcessingUpload(String lastProgress) {
        return jdbc.queryForObject("INSERT INTO bulk_uploads (college_id, uploaded_by_user_id, entity_type, file_name, "
                        + "total_rows, successful_rows, failed_rows, status, last_progress_at) "
                        + "VALUES (?, ?, 'STUDENT', 'x.csv', 0, 0, 0, 'PROCESSING', " + lastProgress + ") RETURNING id",
                Long.class, fixture.collegeId, fixture.adminUserId);
    }

    private long uploadAndWait(String csv) throws Exception {
        JsonNode response = json(upload(csv.getBytes(StandardCharsets.UTF_8)).andExpect(status().isAccepted()));
        long id = response.path("uploadId").asLong();
        awaitDone(id);
        return id;
    }

    private void awaitDone(long uploadId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            String status = jdbc.queryForObject("SELECT status FROM bulk_uploads WHERE id = ?", String.class, uploadId);
            if (!"PROCESSING".equals(status)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("upload " + uploadId + " still PROCESSING after 60 s");
    }

    private ResultActions upload(byte[] content) throws Exception {
        return mvc.perform(auth(multipart("/api/v1/admin/students/bulk-upload")
                .file(new MockMultipartFile("file", "students.csv", "text/csv", content))));
    }

    private <T extends org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder> T auth(T request) {
        request.header("Authorization", token);
        return request;
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static byte[] multipartBody(String content) {
        return ("--csvimporttest\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"big.csv\"\r\n"
                + "Content-Type: text/csv\r\n\r\n"
                + content + "\r\n--csvimporttest--\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
