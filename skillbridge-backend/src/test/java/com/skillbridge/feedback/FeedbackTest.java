package com.skillbridge.feedback;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Feedback, over HTTP. {@code feedback.service} was at <b>9.8%</b> of its lines
 * and has a screen for two different roles.
 *
 * <p>The interesting property is that **the client does not decide who is
 * speaking**. The request body still carries a {@code type} field, and the
 * service ignores it: the direction comes from the caller's role and the author
 * comes from the security principal. A client that could set either could file
 * feedback as somebody else — a student writing a glowing
 * {@code TRAINER_TO_STUDENT} review of themselves, or a trainer writing one
 * "from" a student.
 *
 * <p>The table stores user ids; the API speaks profile ids
 * ({@code students.id}, {@code trainers.id}). That translation is the other
 * thing worth pinning, because it is invisible in the response until it is
 * wrong — and when it is wrong, the feedback is attached to the wrong person.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class FeedbackTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private TenantFixture home;
    private TenantFixture other;

    private Long batchId;
    private Long studentId;
    private Long trainerId;
    private String studentToken;
    private String trainerToken;
    private String adminToken;

    @BeforeEach
    void seed() {
        home = new TenantFixture(jdbc, "FEEDIT");
        home.seed(1, 1, 1);
        batchId = home.batchIds.get(0);
        studentId = home.studentIds.get(0);
        trainerId = home.trainerId;

        other = new TenantFixture(jdbc, "FEEDOTHER");
        other.seed(1, 1, 1);

        studentToken = bearer(home.studentUserIds.get(0), "STUDENT");
        trainerToken = bearer(home.trainerUserId, "TRAINER");
        adminToken = bearer(home.adminUserId, "COLLEGE_ADMIN");
    }

    @AfterEach
    void cleanUp() {
        home.remove();
        other.remove();
    }

    // ------------------------------------------------------------- direction

    @Test
    @DisplayName("a trainer's feedback is stored as TRAINER_TO_STUDENT, from the trainer")
    void trainerFeedbackGoesToTheStudent() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"rating":4,
                                 "category":"Attendance","comments":"Improving"}
                                """.formatted(batchId, studentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRAINER_TO_STUDENT"))
                .andExpect(jsonPath("$.studentId").value(studentId))
                .andExpect(jsonPath("$.trainerId").value(trainerId))
                .andExpect(jsonPath("$.rating").value(4));

        // The row stores user ids; the response speaks profile ids. Both ends
        // of that translation have to be right or the feedback lands on
        // somebody else.
        assertThat(rowsFromTo(home.trainerUserId, home.studentUserIds.get(0))).isEqualTo(1);
    }

    @Test
    @DisplayName("a student's feedback is stored as STUDENT_TO_TRAINER, from the student")
    void studentFeedbackGoesToTheTrainer() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"trainerId":%d,"rating":5,
                                 "category":"Teaching","comments":"Clear explanations"}
                                """.formatted(batchId, trainerId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("STUDENT_TO_TRAINER"));

        assertThat(rowsFromTo(home.studentUserIds.get(0), home.trainerUserId)).isEqualTo(1);
    }

    @Test
    @DisplayName("the type in the body is ignored; the caller's role decides")
    void theBodyCannotChooseTheDirection() throws Exception {
        // A student claiming to be writing a trainer's review of a student. If
        // this were honoured, anyone could file feedback as anyone.
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"trainerId":%d,"studentId":%d,
                                 "type":"TRAINER_TO_STUDENT","rating":5,
                                 "category":"Teaching","comments":"."}
                                """.formatted(batchId, trainerId, studentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("STUDENT_TO_TRAINER"));

        assertThat(rowsFromTo(home.studentUserIds.get(0), home.trainerUserId)).isEqualTo(1);
        assertThat(rowsFromTo(home.trainerUserId, home.studentUserIds.get(0)))
                .as("the body's type was honoured; a student filed a trainer's review")
                .isZero();
    }

    @Test
    @DisplayName("a student must name a trainer, and a trainer a student")
    void theRequiredCounterpartyDependsOnTheCaller() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"rating":5,"category":"Teaching"}
                                """.formatted(batchId, studentId)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"trainerId":%d,"rating":5,"category":"Teaching"}
                                """.formatted(batchId, trainerId)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- refusals

    @Test
    @DisplayName("nobody reviews themselves")
    void cannotReviewYourself() throws Exception {
        // A trainer who is somehow also a student of their own batch. Contrived,
        // and the check is one line — but without it the row exists and every
        // average is quietly wrong.
        Long selfStudentId = insertStudentForUser(home, home.trainerUserId, "FEEDIT-SELF");

        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"rating":5,"category":"Self"}
                                """.formatted(batchId, selfStudentId)))
                .andExpect(status().isBadRequest());

        assertThat(rowsFromTo(home.trainerUserId, home.trainerUserId)).isZero();
    }

    @Test
    @DisplayName("a rating outside 1-5 is refused before anything is written")
    void ratingIsBounded() throws Exception {
        for (String rating : new String[]{"0", "6", "-1"}) {
            mvc.perform(post("/api/v1/feedback")
                            .header("Authorization", trainerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"batchId":%d,"studentId":%d,"rating":%s,"category":"Attendance"}
                                    """.formatted(batchId, studentId, rating)))
                    .andExpect(status().isBadRequest());
        }
        assertThat(rowsInBatch(batchId)).isZero();
    }

    @Test
    @DisplayName("a blank category is refused, because the screen groups by it")
    void categoryIsRequired() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"rating":3,"category":"   "}
                                """.formatted(batchId, studentId)))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------- tenancy

    @Test
    @DisplayName("a trainer cannot leave feedback about another college's student")
    void cannotReachAcrossColleges() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"rating":1,"category":"Attendance"}
                                """.formatted(batchId, other.studentIds.get(0))))
                // 404: a 403 would confirm that student exists.
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("nor against another college's batch")
    void cannotUseAnotherCollegesBatch() throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"rating":1,"category":"Attendance"}
                                """.formatted(other.batchIds.get(0), studentId)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("and cannot read another college's batch feedback")
    void cannotReadAnotherCollegesBatch() throws Exception {
        mvc.perform(get("/api/v1/feedback/batch/{id}", other.batchIds.get(0))
                        .header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a student cannot read another student's feedback by guessing an id")
    void studentsCannotReadTheStudentEndpoint() throws Exception {
        // This endpoint used to admit STUDENT with a TODO about adding an
        // ownership check, so incrementing the id walked the whole college.
        mvc.perform(get("/api/v1/feedback/student/{id}", studentId)
                        .header("Authorization", studentToken))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------- retrieval

    @Test
    @DisplayName("my-feedback shows both what I wrote and what was written about me")
    void myFeedbackIsBothDirections() throws Exception {
        leaveTrainerFeedback("Attendance");
        leaveStudentFeedback("Teaching");

        mvc.perform(get("/api/v1/feedback/my-feedback")
                        .header("Authorization", studentToken))
                .andExpect(status().isOk())
                // One received, one given. A query that only looked at
                // `to_user_id` would show the student a page that never
                // contains anything they wrote.
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @DisplayName("a batch's feedback is visible to the staff who run it")
    void staffSeeTheBatchFeedback() throws Exception {
        leaveTrainerFeedback("Attendance");

        mvc.perform(get("/api/v1/feedback/batch/{id}", batchId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].category").value("Attendance"))
                .andExpect(jsonPath("$.items[0].studentName").isNotEmpty())
                .andExpect(jsonPath("$.items[0].trainerName").isNotEmpty());
    }

    @Test
    @DisplayName("feedback about one student is exactly that student's")
    void studentFeedbackIsScopedToTheStudent() throws Exception {
        leaveTrainerFeedback("Attendance");
        Long secondStudent = insertStudentForUser(home,
                insertUser(home, "second@feedit.example.invalid"), "FEEDIT-2");

        mvc.perform(get("/api/v1/feedback/student/{id}", secondStudent)
                        .header("Authorization", trainerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/v1/feedback/student/{id}", studentId)
                        .header("Authorization", trainerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------- util

    private void leaveTrainerFeedback(String category) throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"rating":4,"category":"%s","comments":"."}
                                """.formatted(batchId, studentId, category)))
                .andExpect(status().isCreated());
    }

    private void leaveStudentFeedback(String category) throws Exception {
        mvc.perform(post("/api/v1/feedback")
                        .header("Authorization", studentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"trainerId":%d,"rating":5,"category":"%s","comments":"."}
                                """.formatted(batchId, trainerId, category)))
                .andExpect(status().isCreated());
    }

    private String bearer(Long userId, String role) {
        User user = userRepository.findById(userId).orElseThrow();
        return TestAuthentication.bearer(jwtService, user, role);
    }

    private int rowsFromTo(Long fromUserId, Long toUserId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM feedback WHERE from_user_id = ? AND to_user_id = ?",
                Integer.class, fromUserId, toUserId);
    }

    private int rowsInBatch(Long batch) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM feedback WHERE batch_id = ?", Integer.class, batch);
    }

    private Long insertUser(TenantFixture fixture, String email) {
        Long id = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at)
                VALUES (?, 'not-a-real-hash', ?, true, now(), now())
                RETURNING id
                """, Long.class, email, fixture.collegeId);
        fixture.studentUserIds.add(id);
        return id;
    }

    /** A student profile for an existing user, so the two can be the same person. */
    private Long insertStudentForUser(TenantFixture fixture, Long userId, String roll) {
        Long id = jdbc.queryForObject("""
                INSERT INTO students (user_id, college_id, full_name, roll_number, degree, branch,
                                      year, created_at, updated_at)
                VALUES (?, ?, 'Extra Student', ?, 'B.Tech', 'CSE', 3, now(), now())
                RETURNING id
                """, Long.class, userId, fixture.collegeId, roll);
        fixture.studentIds.add(id);
        return id;
    }
}
