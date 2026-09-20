package com.skillbridge.skillgap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TestAuthentication;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The report from the row the AI service writes to the screen's JSON.
 *
 * <p>The row is inserted exactly as report_store.py writes it: the contract's
 * own example document in the jsonb column. So this is the crossing between
 * the two services, not two ends tested separately (START-HERE, trap 5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class SkillGapReportTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private TenantFixture fixture;
    private Long studentId;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, "SKILLGAP");
        fixture.seed(0, 1);
        studentId = fixture.studentIds.get(0);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", String.valueOf(studentId));
        fixture.remove(); // skill_gap_reports goes with the student (ON DELETE CASCADE)
    }

    @Test
    @DisplayName("before any analysis the student gets 204, not an error and not an empty report")
    void noReportYet() throws Exception {
        mvc.perform(get("/api/v1/students/me/skill-gap").header("Authorization", student()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("the stored document reaches the student and their college admin intact")
    void reportIsShown() throws Exception {
        storeReport("success.example.json");

        record Caller(String authorization, String path) { }
        for (Caller caller : new Caller[]{
                new Caller(student(), "/api/v1/students/me/skill-gap"),
                new Caller(admin(), "/api/v1/admin/students/" + studentId + "/skill-gap")}) {
            mvc.perform(get(caller.path()).header("Authorization", caller.authorization()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.matchedJobs[0].title").value("Sr. Programmer Analyst - Big Data"))
                    .andExpect(jsonPath("$.matchedJobs[0].similarity").value(0.624))
                    .andExpect(jsonPath("$.matchedJobs[0].missingSkills[1]").value("spark"))
                    .andExpect(jsonPath("$.missingSkills.length()").value(3))
                    .andExpect(jsonPath("$.analyzedAt").value("2026-09-19T16:10:05.123456"));
        }
    }

    @Test
    @DisplayName("asking for a fresh analysis queues a PROFILE_UPDATED event for the AI service")
    void refreshQueuesAnEvent() throws Exception {
        mvc.perform(post("/api/v1/students/me/skill-gap/refresh").header("Authorization", student()))
                .andExpect(status().isAccepted());

        assertThat(jdbc.queryForList(
                "SELECT event_type FROM outbox_events WHERE aggregate_type = 'Student' AND aggregate_id = ?",
                String.class, String.valueOf(studentId))).containsExactly("PROFILE_UPDATED");
    }

    private void storeReport(String example) throws Exception {
        ObjectNode document = (ObjectNode) objectMapper.readTree(
                Files.readString(contract().resolve(example)));
        document.put("studentId", studentId);
        jdbc.update("""
                INSERT INTO skill_gap_reports (student_id, college_id, status, schema_version, report, analyzed_at)
                VALUES (?, ?, ?, 1, ?::jsonb, '2026-09-19 16:10:05.123456')
                """, studentId, fixture.collegeId, document.path("status").asText(), document.toString());
    }

    private String student() {
        return TestAuthentication.bearer(jwtService,
                userRepository.findById(fixture.studentUserIds.get(0)).orElseThrow(), "STUDENT");
    }

    private String admin() {
        return TestAuthentication.bearer(jwtService,
                userRepository.findById(fixture.adminUserId).orElseThrow(), "COLLEGE_ADMIN");
    }

    private static Path contract() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("contracts"))) {
                return p.resolve("contracts/skill-gap-report/v1");
            }
        }
        throw new IllegalStateException("contracts/ not found");
    }
}
