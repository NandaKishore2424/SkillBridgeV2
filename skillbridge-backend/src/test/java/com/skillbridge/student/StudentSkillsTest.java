package com.skillbridge.student;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import com.skillbridge.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A student's skills, and the event that keeps the AI report honest.
 *
 * <p>Every change to a student's skills has to reach the AI service, because
 * nothing else re-runs the analysis: the report is written once per event and
 * then just sits there. {@code addSkill} and {@code updateSkillProficiency}
 * published; {@code removeSkill} did not. So a student who removed a skill kept
 * a report built on it — still being matched against jobs on the strength of
 * something no longer on their profile, permanently. That is the moment the
 * report is most wrong and most worth refreshing.
 *
 * <p>The assertions are on {@code outbox_events} rather than on a mocked
 * publisher. The outbox is the contract — a row in the same transaction as the
 * write — and mocking the publisher would pass for an implementation that
 * publishes outside the transaction, which is the failure the outbox exists to
 * prevent.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class StudentSkillsTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private TenantFixture fixture;
    private Long studentId;
    private Long studentUserId;
    private Long skillId;
    private Long otherSkillId;
    private String studentToken;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, "SKILLSIT");
        fixture.seed(1, 1, 1);
        studentId = fixture.studentIds.get(0);
        studentUserId = fixture.studentUserIds.get(0);
        studentToken = bearer(studentUserId, "STUDENT");

        skillId = skillNamed("Java");
        otherSkillId = skillNamed("Kubernetes");
        clearOutbox();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM student_skills WHERE student_id = ?", studentId);
        clearOutbox();
        fixture.remove();
    }

    @Test
    @DisplayName("adding a skill stores it and queues a re-analysis")
    void addingPublishes() throws Exception {
        addSkill(skillId, 3);

        assertThat(skillRows()).isEqualTo(1);
        assertThat(skillUpdatedEvents())
                .as("nothing told the AI service, so the report will never mention this skill")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("adding the same skill twice is a conflict, not a duplicate row")
    void addingTwiceConflicts() throws Exception {
        addSkill(skillId, 3);

        mvc.perform(post("/api/v1/students/me/skills")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":%d,\"proficiencyLevel\":4}".formatted(skillId)))
                .andExpect(status().isConflict());

        assertThat(skillRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("changing a proficiency queues a re-analysis")
    void updatingPublishes() throws Exception {
        addSkill(skillId, 2);
        clearOutbox();

        mvc.perform(put("/api/v1/students/me/skills/{id}", skillId)
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proficiencyLevel\":5}"))
                .andExpect(status().isOk());

        assertThat(proficiency(skillId)).isEqualTo(5);
        assertThat(skillUpdatedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("removing a skill queues a re-analysis too")
    void removingPublishes() throws Exception {
        addSkill(skillId, 3);
        clearOutbox();

        mvc.perform(delete("/api/v1/students/me/skills/{id}", skillId)
                        .header("Authorization", studentToken))
                .andExpect(status().isOk());

        assertThat(skillRows()).isZero();
        // This is the one that was missing. Without it the student's report
        // keeps matching them against jobs on the strength of a skill they have
        // just said they do not have, and nothing will ever correct it.
        assertThat(skillUpdatedEvents())
                .as("the skill is gone and the report still counts it")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("removing a skill the student never had queues nothing")
    void removingSomethingAbsentIsQuiet() throws Exception {
        mvc.perform(delete("/api/v1/students/me/skills/{id}", otherSkillId)
                        .header("Authorization", studentToken))
                .andExpect(status().isOk());

        // The delete button is easy to press twice. A no-op must not queue AI
        // work — the analysis is the expensive part of this system.
        assertThat(skillUpdatedEvents()).isZero();
    }

    @Test
    @DisplayName("a second delete of the same skill is quiet as well")
    void deletingTwiceQueuesOnce() throws Exception {
        addSkill(skillId, 3);
        clearOutbox();

        for (int i = 0; i < 2; i++) {
            mvc.perform(delete("/api/v1/students/me/skills/{id}", skillId)
                            .header("Authorization", studentToken))
                    .andExpect(status().isOk());
        }

        assertThat(skillUpdatedEvents()).isEqualTo(1);
    }

    @Test
    @DisplayName("the event names the student, so the consumer analyses the right one")
    void theEventNamesTheStudent() throws Exception {
        addSkill(skillId, 3);

        List<String> payloads = jdbc.queryForList("""
                SELECT payload FROM outbox_events
                WHERE event_type = 'SKILL_UPDATED' AND aggregate_id = ?
                """, String.class, String.valueOf(studentId));

        assertThat(payloads).hasSize(1);
        // Parsed, not string-matched: the column is `jsonb`, and Postgres
        // re-renders it with its own spacing, so `"studentId":1` never appears
        // literally. A test that matched the text would be asserting Postgres's
        // formatting rather than the event's contents.
        JsonNode envelope = new ObjectMapper().readTree(payloads.get(0));

        // The precondition the assertion below depends on. If the two ids ever
        // coincided, sending the wrong one would pass and prove nothing.
        assertThat(studentId).isNotEqualTo(studentUserId);

        assertThat(envelope.path("payload").path("studentId").asLong())
                .as("the consumer is handed a user id and analyses the wrong person")
                .isEqualTo(studentId);
        assertThat(envelope.path("collegeId").asLong()).isEqualTo(fixture.collegeId);
        assertThat(envelope.path("eventType").asText()).isEqualTo("SKILL_UPDATED");
    }

    @Test
    @DisplayName("an unknown skill id is a 404 and writes nothing")
    void unknownSkillIsNotFound() throws Exception {
        mvc.perform(post("/api/v1/students/me/skills")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":987654321,\"proficiencyLevel\":3}"))
                .andExpect(status().isNotFound());

        assertThat(skillRows()).isZero();
        assertThat(skillUpdatedEvents()).isZero();
    }

    @Test
    @DisplayName("changing a proficiency the student does not hold is a 404")
    void updatingSomethingAbsentIsNotFound() throws Exception {
        mvc.perform(put("/api/v1/students/me/skills/{id}", otherSkillId)
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proficiencyLevel\":5}"))
                .andExpect(status().isNotFound());

        assertThat(skillUpdatedEvents()).isZero();
    }

    // ------------------------------------------------------------------ util

    private void addSkill(Long id, int proficiency) throws Exception {
        mvc.perform(post("/api/v1/students/me/skills")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skillId\":%d,\"proficiencyLevel\":%d}".formatted(id, proficiency)))
                .andExpect(status().isOk());
    }

    private String bearer(Long userId, String role) {
        User user = userRepository.findById(userId).orElseThrow();
        return TestAuthentication.bearer(jwtService, user, role);
    }

    /** The reference skill list is seeded by migration V2; take one that exists. */
    private Long skillNamed(String preferred) {
        List<Long> found = jdbc.queryForList(
                "SELECT id FROM skills WHERE lower(name) = lower(?) LIMIT 1", Long.class, preferred);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        return jdbc.queryForObject("""
                INSERT INTO skills (name, category, created_at)
                VALUES (?, 'Test', now())
                RETURNING id
                """, Long.class, preferred + " " + System.nanoTime());
    }

    private int skillRows() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM student_skills WHERE student_id = ?", Integer.class, studentId);
    }

    private int proficiency(Long id) {
        return jdbc.queryForObject("""
                SELECT proficiency_level FROM student_skills WHERE student_id = ? AND skill_id = ?
                """, Integer.class, studentId, id);
    }

    private int skillUpdatedEvents() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM outbox_events
                WHERE event_type = 'SKILL_UPDATED' AND aggregate_id = ?
                """, Integer.class, String.valueOf(studentId));
    }

    private void clearOutbox() {
        jdbc.update("DELETE FROM outbox_events WHERE aggregate_id = ?", String.valueOf(studentId));
    }
}
