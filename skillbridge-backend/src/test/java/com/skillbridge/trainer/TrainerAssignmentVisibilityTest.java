package com.skillbridge.trainer;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.batch.service.BatchAssignmentService;
import com.skillbridge.common.exception.ForbiddenException;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.trainer.service.TrainerDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static com.skillbridge.common.dto.Pagination.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Assigns a trainer the way an admin does, then reads it back the way the
 * trainer does.
 *
 * <p>This is the test whose absence let a bug live for months. The two sides
 * used different tables: {@link BatchAssignmentService} wrote
 * {@code batch_trainers}, the {@code @ManyToMany} on {@code Batch}, while the
 * whole trainer side read {@code trainer_batches} — a second table modelling
 * the same relationship that <em>nothing in the application ever wrote</em>.
 *
 * <p>Nothing failed. No exception, no log line. An admin assigned a trainer,
 * the admin's own screen showed the assignment, and the trainer's dashboard
 * showed an empty list — which reads exactly like "you have no batches yet".
 * Worse, {@code ProgressService}'s authorisation gate asked the same empty
 * table, so no trainer could ever be permitted to grade and Phase 04 was
 * unreachable in production.
 *
 * <p>Every test that existed passed. Each one stayed on its own side of the
 * boundary: the admin tests asserted the write, and the trainer tests seeded
 * the table they read. Only a test that crosses can catch this, which is the
 * one thing this file does.
 */
@SpringBootTest
@IntegrationTest
class TrainerAssignmentVisibilityTest {

    private static final String COLLEGE_CODE = "TRNVISIBILITY";

    @Autowired
    private BatchAssignmentService batchAssignmentService;

    @Autowired
    private TrainerDashboardService dashboardService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long trainerId;
    private Long trainerUserId;
    private Long otherTrainerUserId;
    private Long batchId;
    private Long collegeId;

    @BeforeEach
    void seed() {
        removeFixture();

        jdbc.update("""
                INSERT INTO colleges (name, code, email, phone, address, status, created_at, updated_at)
                VALUES ('Trainer Visibility Test College', ?, 'trainer-visibility@example.invalid',
                        '0000000000', 'n/a', 'ACTIVE', now(), now())
                """, COLLEGE_CODE);
        collegeId = jdbc.queryForObject(
                "SELECT id FROM colleges WHERE code = ?", Long.class, COLLEGE_CODE);

        trainerUserId = insertUser(collegeId, "assigned.trainer@example.invalid");
        trainerId = insertTrainer(collegeId, trainerUserId, "Assigned Trainer");

        // A second trainer, never assigned, so "can see it" is proved against
        // "cannot see it" rather than against nothing.
        otherTrainerUserId = insertUser(collegeId, "other.trainer@example.invalid");
        insertTrainer(collegeId, otherTrainerUserId, "Unassigned Trainer");

        batchId = jdbc.queryForObject("""
                INSERT INTO batches (college_id, name, description, status,
                                     start_date, end_date, created_at, updated_at, version)
                VALUES (?, 'Visibility Fixture Batch', 'fixture', 'ACTIVE',
                        CURRENT_DATE, CURRENT_DATE + 30, now(), now(), 0)
                RETURNING id
                """, Long.class, collegeId);

        // BatchAssignmentService is the real admin path, so it runs the real
        // TenantGuard. Acting as an admin of this fixture's college is part of
        // what makes the test faithful rather than a shortcut around it.
        authenticateAsAdminOfFixtureCollege();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        removeFixture();
    }

    private void authenticateAsAdminOfFixtureCollege() {
        SecurityContextHolder.clearContext();

        Role role = new Role();
        role.setName("COLLEGE_ADMIN");

        User user = User.builder()
                .id(-1L)
                .email("trainer-visibility-admin@example.invalid")
                .collegeId(collegeId)
                .isActive(true)
                .roles(Set.of(role))
                .build();

        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("a trainer assigned by an admin sees the batch on their own dashboard")
    void assignmentIsVisibleToTheTrainer() {
        assertThat(dashboardService.getTrainerBatches(trainerUserId, of(0, 20)))
                .as("precondition: the trainer starts with nothing")
                .isEmpty();

        // The admin's path, exactly as AdminBatchController calls it.
        batchAssignmentService.assignTrainer(batchId, trainerId);

        assertThat(dashboardService.getTrainerBatches(trainerUserId, of(0, 20)).getContent())
                .as("the write and the read must agree; they used to use different tables")
                .extracting(dto -> dto.getId())
                .containsExactly(batchId);

        assertThat(dashboardService.getDashboardStats(trainerUserId).getAssignedBatches())
                .as("the counters read the same relationship")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("assignment is what grants access to the batch's students, and unassignment removes it")
    void assignmentGatesTheStudentList() {
        assertThatThrownBy(() -> dashboardService.getBatchStudents(trainerUserId, batchId, of(0, 20)))
                .as("no assignment, no access")
                .isInstanceOf(ForbiddenException.class);

        batchAssignmentService.assignTrainer(batchId, trainerId);

        assertThat(dashboardService.getBatchStudents(trainerUserId, batchId, of(0, 20)))
                .as("assigned: the call succeeds, empty only because nobody is enrolled")
                .isEmpty();

        // The reverse direction matters too: a revoked assignment that keeps
        // working is the same class of bug pointing the other way.
        batchAssignmentService.unassignTrainer(batchId, trainerId);

        assertThatThrownBy(() -> dashboardService.getBatchStudents(trainerUserId, batchId, of(0, 20)))
                .as("unassigned: access is withdrawn")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("one trainer's assignment is not another's")
    void assignmentIsNotShared() {
        batchAssignmentService.assignTrainer(batchId, trainerId);

        assertThat(dashboardService.getTrainerBatches(otherTrainerUserId, of(0, 20)))
                .as("an unassigned trainer sees nothing — otherwise the query ignores its parameter")
                .isEmpty();
        assertThatThrownBy(() -> dashboardService.getBatchStudents(otherTrainerUserId, batchId, of(0, 20)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("a soft-deleted batch drops off the trainer's dashboard")
    void softDeletedBatchesAreHidden() {
        batchAssignmentService.assignTrainer(batchId, trainerId);
        assertThat(dashboardService.getTrainerBatches(trainerUserId, of(0, 20))).hasSize(1);

        jdbc.update("UPDATE batches SET deleted_at = now() WHERE id = ?", batchId);

        // Batch carries @SQLRestriction("deleted_at IS NULL"), so rooting the
        // query at Batch gets this for free. The old query was rooted at the
        // join row and would have kept returning it.
        assertThat(dashboardService.getTrainerBatches(trainerUserId, of(0, 20)))
                .as("a deleted batch is not a batch the trainer still teaches")
                .isEmpty();
    }

    // ------------------------------------------------------------------

    private Long insertUser(Long collegeId, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at)
                VALUES (?, 'not-a-real-hash', ?, true, now(), now())
                RETURNING id
                """, Long.class, email, collegeId);
    }

    private Long insertTrainer(Long collegeId, Long userId, String fullName) {
        return jdbc.queryForObject("""
                INSERT INTO trainers (user_id, college_id, full_name, phone, department,
                                      specialization, created_at, updated_at)
                VALUES (?, ?, ?, '0000000000', 'QA', 'Testing', now(), now())
                RETURNING id
                """, Long.class, userId, collegeId, fullName);
    }

    /** Leaf-first, so no foreign key blocks the delete. */
    private void removeFixture() {
        String collegeIds = "SELECT id FROM colleges WHERE code = '" + COLLEGE_CODE + "'";
        jdbc.update("DELETE FROM batch_trainers WHERE batch_id IN "
                + "(SELECT id FROM batches WHERE college_id IN (" + collegeIds + "))");
        jdbc.update("DELETE FROM batches WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM trainers WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM users WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM colleges WHERE code = ?", COLLEGE_CODE);
    }
}
