package com.skillbridge.persistence;

import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two partial unique indexes carry rules the Java code does not state anywhere.
 *
 * <p>Both are <i>partial</i>, and the predicate is the whole design. A plain
 * unique index in either place would be wrong in a way that only shows up in
 * use: a student rejected from a batch could never apply again, and a roll
 * number could never be reissued after a student left.
 *
 * <p>This is a {@code @DataJpaTest} against a real PostgreSQL, which is the only
 * way to assert any of it. <b>H2 does not support partial indexes at all</b>, so
 * an H2 suite would create neither index, accept every insert below, and report
 * green -- the most expensive kind of passing test. That is why Phase 11 says
 * "Testcontainers, never H2", and why this file could not exist before
 * {@code db/schema/baseline.sql} made a matching database buildable.
 *
 * <p>{@code replace = NONE} is what stops Spring swapping in an embedded
 * database behind your back; without it {@code @DataJpaTest} would quietly do
 * exactly the thing the paragraph above warns about.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@IntegrationTest
class PartialUniqueIndexTest {

    private static final String COLLEGE_CODE = "PARTIALIDXTEST";

    @Autowired
    private JdbcTemplate jdbc;

    private Long collegeId;
    private Long batchId;
    private Long studentId;

    @BeforeEach
    void seed() {
        collegeId = insertCollege();
        batchId = insertBatch(collegeId);
        studentId = insertStudent(collegeId, "ROLL-001");
    }

    // ------------------------------------------- uk_requests_one_pending...

    @Test
    @DisplayName("a student cannot have two pending requests for the same batch")
    void twoPendingRequestsAreRejected() {
        insertRequest("PENDING");

        assertThatThrownBy(() -> insertRequest("PENDING"))
                .as("the second PENDING request must collide with the partial unique index")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("a rejected student may apply again, which a plain unique index would forbid")
    void reapplicationAfterRejectionIsAllowed() {
        // The index covers only WHERE status = 'PENDING'. A rejected row is
        // outside it, so it does not occupy the slot.
        insertRequest("REJECTED");
        insertRequest("REJECTED");
        insertRequest("WITHDRAWN");

        assertThatNoException()
                .as("history must not block a new application")
                .isThrownBy(() -> insertRequest("PENDING"));

        assertThat(countRequests()).isEqualTo(4);
    }

    @Test
    @DisplayName("the constraint is per request type, so an ADD and a REMOVE can both be pending")
    void addAndRemoveAreIndependent() {
        insertRequest("PENDING", "ADD");

        assertThatNoException()
                .as("request_type is part of the index key")
                .isThrownBy(() -> insertRequest("PENDING", "REMOVE"));
    }

    // ------------------------------------------------- uk_students_roll_live

    @Test
    @DisplayName("two live students in one college cannot share a roll number")
    void duplicateLiveRollNumberIsRejected() {
        assertThatThrownBy(() -> insertStudent(collegeId, "ROLL-001"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("a soft-deleted student releases their roll number for reissue")
    void softDeleteFreesTheRollNumber() {
        // WHERE deleted_at IS NULL. This is the reason the index is partial: a
        // plain unique index would keep a departed student's roll number
        // reserved for ever, and the only way to reissue it would be a hard
        // delete -- which soft delete exists to avoid.
        jdbc.update("UPDATE students SET deleted_at = now() WHERE id = ?", studentId);

        assertThatNoException()
                .isThrownBy(() -> insertStudent(collegeId, "ROLL-001"));
    }

    @Test
    @DisplayName("the same roll number in a different college is fine")
    void rollNumbersAreScopedToTheCollege() {
        Long otherCollege = insertCollege();

        assertThatNoException()
                .as("college_id leads the index key; roll numbers are a per-college namespace")
                .isThrownBy(() -> insertStudent(otherCollege, "ROLL-001"));
    }

    // ---------------------------------------------------------------- seed

    private Long insertCollege() {
        String code = COLLEGE_CODE + "-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO colleges (name, code, status, created_at, updated_at)
                VALUES ('Partial Index Test College', ?, 'ACTIVE', now(), now())
                """, code);
        return jdbc.queryForObject("SELECT id FROM colleges WHERE code = ?", Long.class, code);
    }

    private Long insertBatch(Long college) {
        jdbc.update("""
                INSERT INTO batches (college_id, name, status, created_at, updated_at, version)
                VALUES (?, 'Partial Index Test Batch', 'OPEN', now(), now(), 0)
                """, college);
        return jdbc.queryForObject(
                "SELECT id FROM batches WHERE college_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, college);
    }

    private Long insertStudent(Long college, String rollNumber) {
        String email = "partial-" + System.nanoTime() + "@example.invalid";
        jdbc.update("""
                INSERT INTO users (college_id, email, password_hash, is_active, created_at, updated_at)
                VALUES (?, ?, 'x', true, now(), now())
                """, college, email);
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        jdbc.update("""
                INSERT INTO students (user_id, college_id, roll_number, full_name, created_at, updated_at)
                VALUES (?, ?, ?, 'Partial Index Test Student', now(), now())
                """, userId, college, rollNumber);
        return jdbc.queryForObject(
                "SELECT id FROM students WHERE user_id = ?", Long.class, userId);
    }

    private void insertRequest(String status) {
        insertRequest(status, "ADD");
    }

    private void insertRequest(String status, String requestType) {
        // source STUDENT_APPLICATION, not TRAINER_REQUEST: chk_requests_trainer_presence
        // requires a trainer_id for the latter, and this test is not about that.
        jdbc.update("""
                INSERT INTO enrollment_requests
                    (batch_id, student_id, college_id, request_type, status, source, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, 'STUDENT_APPLICATION', now(), now(), 0)
                """, batchId, studentId, collegeId, requestType, status);
    }

    private int countRequests() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM enrollment_requests WHERE student_id = ?",
                Integer.class, studentId);
    }
}
