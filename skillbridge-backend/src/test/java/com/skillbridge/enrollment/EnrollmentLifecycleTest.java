package com.skillbridge.enrollment;

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

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Enrolment, end to end over HTTP.
 *
 * <p>This is the busiest path in the demo — a college admin puts students on a
 * batch, a trainer asks for one more, the admin approves it — and
 * {@code enrollment.service} was the least covered package in the backend at
 * <b>6.9%</b> of its lines. What follows is not coverage for its own sake: each
 * test is a thing that would look fine on screen and be wrong underneath.
 *
 * <p>The one that matters most is {@link #enrollingSeedsTheProgressRows()}.
 * Enrolling a student and seeding their {@code topic_progress} rows are two
 * writes, and only the first is visible: the student appears on the batch
 * immediately, and the trainer discovers months later that there is nothing to
 * grade them on. The service does both in one transaction, and nothing until
 * now checked the second.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTest
class EnrollmentLifecycleTest {

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private TenantFixture home;
    private TenantFixture other;
    private String adminToken;
    private String trainerToken;

    /** A batch with a curriculum and a student who is deliberately NOT on it. */
    private Long batchId;
    private Long unenrolledStudentId;

    @BeforeEach
    void seed() {
        // One batch, three students, one curriculum module. `seed` enrols every
        // student it creates, so the spare student is inserted afterwards.
        home = new TenantFixture(jdbc, "ENROLIT");
        home.seed(1, 2, 1);
        batchId = home.batchIds.get(0);
        unenrolledStudentId = insertSpareStudent(home, "spare@enrolit.example.invalid", "ENROLIT-SPARE");

        other = new TenantFixture(jdbc, "ENROLOTHER");
        other.seed(1, 1, 1);

        adminToken = bearer(home.adminUserId, "COLLEGE_ADMIN");
        trainerToken = bearer(home.trainerUserId, "TRAINER");
    }

    @AfterEach
    void cleanUp() {
        home.remove();
        other.remove();
    }

    // ---------------------------------------------------------------- direct

    @Test
    @DisplayName("enrolling a student seeds the progress rows the trainer grades")
    void enrollingSeedsTheProgressRows() throws Exception {
        assertThat(topicProgressRows(unenrolledStudentId)).isZero();

        mvc.perform(post("/api/v1/admin/batches/{b}/enrollments/{s}", batchId, unenrolledStudentId)
                        .header("Authorization", adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.studentId").value(unenrolledStudentId));

        // The fixture's curriculum is 1 module x 2 sub-modules x 2 topics.
        // Without this assertion the student is enrolled, visible on the batch,
        // and has nothing a trainer can record an outcome against.
        assertThat(topicProgressRows(unenrolledStudentId))
                .as("enrolled with no progress rows: the grading grid would be empty")
                .isEqualTo(4);
    }

    @Test
    @DisplayName("enrolling the same student twice is a 409, not a second row")
    void enrollingTwiceConflicts() throws Exception {
        enrol(unenrolledStudentId);

        mvc.perform(post("/api/v1/admin/batches/{b}/enrollments/{s}", batchId, unenrolledStudentId)
                        .header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_ENROLLED"));

        assertThat(enrolments(unenrolledStudentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("removing a student discards their progress with them")
    void removingDiscardsProgress() throws Exception {
        enrol(unenrolledStudentId);
        assertThat(topicProgressRows(unenrolledStudentId)).isEqualTo(4);

        mvc.perform(delete("/api/v1/admin/batches/{b}/enrollments/{s}", batchId, unenrolledStudentId)
                        .header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        assertThat(enrolments(unenrolledStudentId)).isZero();
        // Orphaned progress rows make every later count wrong, and the cause is
        // a long way from the symptom.
        assertThat(topicProgressRows(unenrolledStudentId))
                .as("progress rows outlived the enrolment")
                .isZero();
    }

    @Test
    @DisplayName("removing a student who was never on the batch says so")
    void removingSomebodyNotEnrolled() throws Exception {
        mvc.perform(delete("/api/v1/admin/batches/{b}/enrollments/{s}", batchId, unenrolledStudentId)
                        .header("Authorization", adminToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("NOT_ENROLLED"));
    }

    // ---------------------------------------------------------------- tenancy

    @Test
    @DisplayName("another college's student cannot be put on this batch, and gets a 404")
    void cannotEnrolAcrossColleges() throws Exception {
        Long stranger = other.studentIds.get(0);

        mvc.perform(post("/api/v1/admin/batches/{b}/enrollments/{s}", batchId, stranger)
                        .header("Authorization", adminToken))
                // 404, not 403: a 403 confirms the student exists to somebody
                // who should not be able to tell.
                .andExpect(status().isNotFound());

        // `seed` already enrolled them in their own college's batch, so the
        // question is whether they gained one on *this* batch.
        assertThat(enrolmentsOn(batchId, stranger)).isZero();
    }

    @Test
    @DisplayName("and this college's student cannot be put on another college's batch")
    void cannotEnrolIntoAnotherCollegesBatch() throws Exception {
        mvc.perform(post("/api/v1/admin/batches/{b}/enrollments/{s}",
                        other.batchIds.get(0), unenrolledStudentId)
                        .header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------- request queue

    @Test
    @DisplayName("a trainer's ADD request, approved, enrols the student and seeds progress")
    void trainerRequestApproved() throws Exception {
        long requestId = requestAdd(unenrolledStudentId, "Joined the cohort late");

        mvc.perform(get("/api/v1/admin/enrollment-requests/pending")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + requestId + ")].status").value("PENDING"));

        mvc.perform(post("/api/v1/admin/enrollment-requests/{id}/approve", requestId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(enrolments(unenrolledStudentId)).isEqualTo(1);
        assertThat(topicProgressRows(unenrolledStudentId)).isEqualTo(4);
    }

    @Test
    @DisplayName("approving an already-rejected request is a conflict, not a second enrolment")
    void approvingAfterRejectingConflicts() throws Exception {
        long requestId = requestAdd(unenrolledStudentId, "Joined late");

        mvc.perform(post("/api/v1/admin/enrollment-requests/{id}/reject", requestId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Batch is full\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        // The state machine is the only thing standing between this and a
        // second enrolment from a request that was already settled.
        mvc.perform(post("/api/v1/admin/enrollment-requests/{id}/approve", requestId)
                        .header("Authorization", adminToken))
                .andExpect(status().isConflict());

        assertThat(enrolments(unenrolledStudentId)).isZero();
    }

    @Test
    @DisplayName("a rejection keeps the reviewer's reason, beside the applicant's")
    void rejectionKeepsItsReason() throws Exception {
        long requestId = requestAdd(unenrolledStudentId, "Joined late");

        mvc.perform(post("/api/v1/admin/enrollment-requests/{id}/reject", requestId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Batch is full\"}"))
                .andExpect(status().isOk())
                // Two different people talking. The applicant's words are kept,
                // and the reviewer's are no longer write-only.
                .andExpect(jsonPath("$.reason").value("Joined late"))
                .andExpect(jsonPath("$.decisionReason").value("Batch is full"))
                .andExpect(jsonPath("$.reviewedByName").isNotEmpty());
    }

    @Test
    @DisplayName("the reviewer recorded is the admin who clicked, not user id 1")
    void theReviewerIsTheCaller() throws Exception {
        long requestId = requestAdd(unenrolledStudentId, "Joined late");

        mvc.perform(post("/api/v1/admin/enrollment-requests/{id}/approve", requestId)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                // Both handlers hardcoded `Long adminUserId = 1L`, so every
                // decision in the system was attributed to whoever user 1 is --
                // and 404'd outright on any database where user 1 is absent.
                .andExpect(jsonPath("$.reviewedBy").value(home.adminUserId));

        Long storedReviewer = jdbc.queryForObject(
                "SELECT reviewed_by FROM enrollment_requests WHERE id = ?", Long.class, requestId);
        assertThat(storedReviewer).isEqualTo(home.adminUserId);
    }

    @Test
    @DisplayName("a second pending request for the same student is refused")
    void duplicatePendingRequestRefused() throws Exception {
        requestAdd(unenrolledStudentId, "Joined late");

        mvc.perform(post("/api/v1/trainer/enrollment-requests")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(unenrolledStudentId, "Asking again")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an ADD request for somebody already enrolled is refused up front")
    void addRequestForAnEnrolledStudentRefused() throws Exception {
        Long alreadyOn = home.studentIds.get(0);

        mvc.perform(post("/api/v1/trainer/enrollment-requests")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(alreadyOn, "Please add")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an unknown request type is a 400, not an enum crash")
    void unknownRequestTypeIsABadRequest() throws Exception {
        mvc.perform(post("/api/v1/trainer/enrollment-requests")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"batchId":%d,"studentId":%d,"requestType":"MAYBE","reason":"?"}
                                """.formatted(batchId, unenrolledStudentId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the pending queue shows only this college's requests")
    void pendingQueueIsScopedToTheCollege() throws Exception {
        long mine = requestAdd(unenrolledStudentId, "Joined late");
        long theirs = insertPendingRequestFor(other);

        String body = mvc.perform(get("/api/v1/admin/enrollment-requests/pending")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Two layers hold this up, and it took removing both to see the leak.
        //
        // Measured 2026-09-20: take the `collegeId` predicate back out of the
        // query and this still returns one college, because `TenantFilterAspect`
        // enables Hibernate's `collegeFilter` on the session inside the
        // transaction. Disable that as well and the other college's request
        // appears here, exactly as it did before either existed.
        //
        // Worth knowing, because the service's own comment used to say the
        // filter "has never actually applied" — true when it was written, and
        // false since the aspect was added. The predicate is still the control;
        // the filter does nothing for a SYSTEM_ADMIN, nothing outside a
        // transaction, and nothing for a findById.
        assertThat(body).contains("\"id\":" + mine);
        assertThat(body)
                .as("another college's pending request leaked into the queue")
                .doesNotContain("\"id\":" + theirs);
    }

    @Test
    @DisplayName("a student application carries no trainer, and the queue still renders")
    void studentApplicationHasNoTrainer() throws Exception {
        // `convertToRequestDTO` used to call `request.getTrainer().getId()`
        // unguarded, so one student application made the whole admin queue 500.
        long applicationId = insertStudentApplication();

        mvc.perform(get("/api/v1/admin/enrollment-requests/pending")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == " + applicationId + ")].trainerId")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.nullValue())));
    }

    // ------------------------------------------------------------------ util

    private void enrol(Long studentId) throws Exception {
        mvc.perform(post("/api/v1/admin/batches/{b}/enrollments/{s}", batchId, studentId)
                        .header("Authorization", adminToken))
                .andExpect(status().isCreated());
    }

    private long requestAdd(Long studentId, String reason) throws Exception {
        String body = mvc.perform(post("/api/v1/trainer/enrollment-requests")
                        .header("Authorization", trainerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addRequestBody(studentId, reason)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        // Parsed, not pattern-matched off the front of the string: the field
        // order in the DTO is not a contract, and a regex that silently stops
        // matching would make every test below act on request id 0.
        Number id = JsonPath.read(body, "$.id");
        return id.longValue();
    }

    private String addRequestBody(Long studentId, String reason) {
        return """
                {"batchId":%d,"studentId":%d,"requestType":"ADD","reason":"%s"}
                """.formatted(batchId, studentId, reason);
    }

    private String bearer(Long userId, String role) {
        User user = userRepository.findById(userId).orElseThrow();
        return TestAuthentication.bearer(jwtService, user, role);
    }

    private int topicProgressRows(Long studentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM topic_progress WHERE student_id = ?", Integer.class, studentId);
    }

    private int enrolments(Long studentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM enrollments WHERE student_id = ?", Integer.class, studentId);
    }

    private int enrolmentsOn(Long batch, Long studentId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM enrollments WHERE batch_id = ? AND student_id = ?",
                Integer.class, batch, studentId);
    }

    /** A student in the college who is on no batch, which `seed` never leaves. */
    private Long insertSpareStudent(TenantFixture fixture, String email, String roll) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at)
                VALUES (?, 'not-a-real-hash', ?, true, now(), now())
                RETURNING id
                """, Long.class, email, fixture.collegeId);
        Long studentId = jdbc.queryForObject("""
                INSERT INTO students (user_id, college_id, full_name, roll_number, degree, branch,
                                      year, created_at, updated_at)
                VALUES (?, ?, 'Spare Student', ?, 'B.Tech', 'CSE', 3, now(), now())
                RETURNING id
                """, Long.class, userId, fixture.collegeId, roll);
        // So TenantFixture.remove() takes them with it.
        fixture.studentIds.add(studentId);
        fixture.studentUserIds.add(userId);
        return studentId;
    }

    /** A pending request belonging to a different college entirely. */
    private long insertPendingRequestFor(TenantFixture fixture) {
        return jdbc.queryForObject("""
                INSERT INTO enrollment_requests (batch_id, student_id, trainer_id, college_id, source,
                                                 request_type, status, reason, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, 'TRAINER_REQUEST', 'ADD', 'PENDING', 'other college',
                        now(), now(), 0)
                RETURNING id
                """, Long.class,
                fixture.batchIds.get(0), fixture.studentIds.get(0), fixture.trainerId, fixture.collegeId);
    }

    /** A request raised by a student, which has no trainer at all. */
    private long insertStudentApplication() {
        return jdbc.queryForObject("""
                INSERT INTO enrollment_requests (batch_id, student_id, trainer_id, college_id, source,
                                                 request_type, status, reason, created_at, updated_at, version)
                VALUES (?, ?, NULL, ?, 'STUDENT_APPLICATION', 'ADD', 'PENDING', 'I would like to join',
                        now(), now(), 0)
                RETURNING id
                """, Long.class, batchId, unenrolledStudentId, home.collegeId);
    }
}
