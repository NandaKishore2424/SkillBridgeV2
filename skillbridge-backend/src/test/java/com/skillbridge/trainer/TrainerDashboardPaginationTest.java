package com.skillbridge.trainer;

import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.trainer.dto.TrainerBatchDTO;
import com.skillbridge.trainer.dto.TrainerStudentDTO;
import com.skillbridge.trainer.service.TrainerDashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static com.skillbridge.common.dto.Pagination.of;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pages the two trainer dashboard reads against rows that actually exist.
 *
 * <p>The fixture writes {@code batch_trainers} — the {@code @ManyToMany} join
 * table on {@code Batch.trainers}, and the single source of truth for who
 * teaches what. It used to write {@code trainer_batches}, a second table that
 * the trainer side read and <em>nothing</em> ever wrote; that split is fixed
 * and the dead table is gone from the mapping.
 *
 * <p>{@link TrainerAssignmentVisibilityTest} is the test that would have caught
 * the split. This one is about paging, and seeds the join table directly so it
 * can control the ordering and the page boundaries.
 *
 * <p>Everything is seeded and removed here rather than depending on whatever is
 * in the database. Asserting against ambient data is how a paging test passes
 * on one row and proves nothing about the second page.
 */
@SpringBootTest
@IntegrationTest
class TrainerDashboardPaginationTest {

    private static final String COLLEGE_CODE = "TRNPAGETEST";

    @Autowired
    private TrainerDashboardService dashboardService;

    @Autowired
    private JdbcTemplate jdbc;

    private Long trainerUserId;
    private List<Long> batchIds;
    private Long batchWithStudents;

    /** One trainer, three batches, three students enrolled on the first batch. */
    @BeforeEach
    void seed() {
        removeFixture();

        jdbc.update("""
                INSERT INTO colleges (name, code, email, phone, address, status, created_at, updated_at)
                VALUES ('Trainer Paging Test College', ?, 'trainer-paging@example.invalid',
                        '0000000000', 'n/a', 'ACTIVE', now(), now())
                """, COLLEGE_CODE);
        Long collegeId = jdbc.queryForObject(
                "SELECT id FROM colleges WHERE code = ?", Long.class, COLLEGE_CODE);

        trainerUserId = insertUser(collegeId, "trainer.paging@example.invalid");
        Long trainerId = jdbc.queryForObject("""
                INSERT INTO trainers (user_id, college_id, full_name, phone, department,
                                      specialization, created_at, updated_at)
                VALUES (?, ?, 'Paging Fixture Trainer', '0000000000', 'QA', 'Testing', now(), now())
                RETURNING id
                """, Long.class, trainerUserId, collegeId);

        // Deliberately out of start-date order, so ordering is proved rather than
        // inherited from insertion order.
        batchIds = List.of(
                insertBatch(collegeId, "Paging Fixture Batch B", 10),
                insertBatch(collegeId, "Paging Fixture Batch A", 30),
                insertBatch(collegeId, "Paging Fixture Batch C", 20));
        batchIds.forEach(batchId -> jdbc.update(
                "INSERT INTO batch_trainers (trainer_id, batch_id, created_at) VALUES (?, ?, now())",
                trainerId, batchId));

        batchWithStudents = batchIds.get(0);
        for (String name : List.of("Carol Fixture", "Alice Fixture", "Bob Fixture")) {
            enroll(collegeId, batchWithStudents, name);
        }
    }

    @AfterEach
    void tearDown() {
        removeFixture();
    }

    @Test
    @DisplayName("a trainer's batches page in SQL, newest intake first, without repeating a row")
    void trainerBatchesPage() {
        Page<TrainerBatchDTO> first = dashboardService.getTrainerBatches(trainerUserId, of(0, 2));
        Page<TrainerBatchDTO> second = dashboardService.getTrainerBatches(trainerUserId, of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.isFirst()).isTrue();
        assertThat(first.isLast()).isFalse();
        assertThat(second.isLast()).isTrue();

        assertThat(first.getContent()).hasSize(2);
        assertThat(second.getContent()).hasSize(1);

        // Ordered by start date descending, and the two pages are disjoint. Both
        // fail if Hibernate paged in memory over an unordered result.
        assertThat(page(first)).containsExactly("Paging Fixture Batch A", "Paging Fixture Batch C");
        assertThat(page(second)).containsExactly("Paging Fixture Batch B");
    }

    @Test
    @DisplayName("enrolled students page by name, with the email the DTO reads resolved")
    void batchStudentsPage() {
        Page<TrainerStudentDTO> first =
                dashboardService.getBatchStudents(trainerUserId, batchWithStudents, of(0, 2));
        Page<TrainerStudentDTO> second =
                dashboardService.getBatchStudents(trainerUserId, batchWithStudents, of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getContent()).extracting(TrainerStudentDTO::getFullName)
                .containsExactly("Alice Fixture", "Bob Fixture");
        assertThat(second.getContent()).extracting(TrainerStudentDTO::getFullName)
                .containsExactly("Carol Fixture");

        // The mapper reads the email off the lazy `user`, so this checks the DTO
        // comes out fully populated. It does NOT prove the fetch join: the
        // service is @Transactional(readOnly = true), so without the join the
        // email still resolves -- as an extra select per row. Verified by
        // removing the join and watching this assertion stay green. The join is
        // there for the N+1, which this test does not measure; the assertions
        // that do go red on a regression are the ordering and disjointness ones
        // above.
        assertThat(first.getContent()).allSatisfy(
                student -> assertThat(student.getEmail()).endsWith("@example.invalid"));
    }

    @Test
    @DisplayName("the page size cap applies here like everywhere else")
    void pageSizeIsCapped() {
        assertThat(dashboardService.getTrainerBatches(trainerUserId, of(0, 1_000_000)).getSize())
                .isEqualTo(100);
    }

    private List<String> page(Page<TrainerBatchDTO> page) {
        return page.getContent().stream().map(TrainerBatchDTO::getName).toList();
    }

    private Long insertUser(Long collegeId, String email) {
        return jdbc.queryForObject("""
                INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at)
                VALUES (?, 'not-a-real-hash', ?, true, now(), now())
                RETURNING id
                """, Long.class, email, collegeId);
    }

    private Long insertBatch(Long collegeId, String name, int startsInDays) {
        return jdbc.queryForObject("""
                INSERT INTO batches (college_id, name, description, status,
                                     start_date, end_date, created_at, updated_at, version)
                VALUES (?, ?, 'fixture', 'UPCOMING',
                        CURRENT_DATE + ?, CURRENT_DATE + ? + 30, now(), now(), 0)
                RETURNING id
                """, Long.class, collegeId, name, startsInDays, startsInDays);
    }

    private void enroll(Long collegeId, Long batchId, String fullName) {
        String email = fullName.split(" ")[0].toLowerCase() + ".paging@example.invalid";
        Long userId = insertUser(collegeId, email);
        Long studentId = jdbc.queryForObject("""
                INSERT INTO students (user_id, college_id, full_name, roll_number, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                RETURNING id
                """, Long.class, userId, collegeId, fullName, "PAGE-" + userId);
        jdbc.update("""
                INSERT INTO enrollments (batch_id, student_id, college_id, status, enrolled_at)
                VALUES (?, ?, ?, 'ACTIVE', now())
                """, batchId, studentId, collegeId);
    }

    /** Removes the fixture leaf-first, so no foreign key blocks the delete. */
    private void removeFixture() {
        String collegeIds = "SELECT id FROM colleges WHERE code = '" + COLLEGE_CODE + "'";
        jdbc.update("DELETE FROM enrollments WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM batch_trainers WHERE batch_id IN "
                + "(SELECT id FROM batches WHERE college_id IN (" + collegeIds + "))");
        jdbc.update("DELETE FROM batches WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM students WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM trainers WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM users WHERE college_id IN (" + collegeIds + ")");
        jdbc.update("DELETE FROM colleges WHERE code = ?", COLLEGE_CODE);
    }
}
