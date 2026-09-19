package com.skillbridge.progress;

import com.skillbridge.common.dto.Pagination;
import com.skillbridge.progress.dto.GradingGridRowDTO;
import com.skillbridge.progress.service.ProgressService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import com.skillbridge.testsupport.TestAuthentication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A grading grid row has to say whose row it is.
 *
 * <p>Obvious, and it did not. The endpoint mapped to {@code TopicProgressDTO},
 * which names the <em>topic</em> — correct for the student detail view, where
 * each row is a topic and the student is the page, and exactly wrong here, where
 * each row is a student and the topic is the page. Every row of the grid came
 * back with the same topic id and name, differing only by an opaque
 * {@code progressId}.
 *
 * <p>It answered 200 the whole time, which is why nothing caught it: the drift
 * check compares routes, not whether a response can be rendered, and no test
 * asserted that two rows differed. A trainer could not have told whose row was
 * whose, and a client could not have assembled the {@code studentIds} that
 * {@code BulkGradeRequest} requires — so the grading screen this API exists for
 * was never built against it.
 */
@SpringBootTest
@IntegrationTest
class GradingGridTest {

    private static final String COLLEGE_CODE = "GRADINGGRID";

    @Autowired private ProgressService progressService;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;
    private Long topicId;

    @BeforeEach
    void seed() {
        // One batch, three students, one module -- enough that a grid with
        // indistinguishable rows is visibly wrong.
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(1, 3, 1);
        // The service decides tenancy from the caller, as the HTTP layer would.
        TestAuthentication.as(fixture.adminUserId, fixture.collegeId, "COLLEGE_ADMIN");
        topicId = jdbc.queryForObject("""
                SELECT t.id FROM syllabus_topics t
                JOIN syllabus_submodules sm ON sm.id = t.submodule_id
                JOIN syllabus_modules m ON m.id = sm.module_id
                WHERE m.batch_id = ?
                ORDER BY t.id LIMIT 1
                """, Long.class, fixture.batchIds.get(0));

        // TenantFixture enrols students with raw SQL, which does not go through
        // the enrolment path that seeds progress rows. backfillBatch is the
        // production code for exactly this gap, so the grid is populated the way
        // it would be in the application rather than by hand-written inserts.
        int created = progressService.backfillBatch(fixture.batchIds.get(0));
        assertThat(created)
                .as("no progress rows means the grid assertions below would pass on an empty page")
                .isPositive();
    }

    @AfterEach
    void cleanUp() {
        TestAuthentication.clear();
        fixture.remove();
    }

    @Test
    @DisplayName("every row names its student, and no two rows are the same student")
    void rowsAreAttributable() {
        Page<GradingGridRowDTO> grid = progressService.getGradingGrid(topicId, Pagination.of(0, 50));

        assertThat(grid.getContent())
                .as("the fixture enrolled three students on this topic")
                .hasSize(3);

        assertThat(grid.getContent())
                .allSatisfy(row -> {
                    assertThat(row.getStudentId()).as("a row with no student cannot be graded").isNotNull();
                    assertThat(row.getStudentName()).isNotBlank();
                    assertThat(row.getRollNumber()).isNotBlank();
                    assertThat(row.getProgressId()).isNotNull();
                });

        List<Long> studentIds = grid.getContent().stream().map(GradingGridRowDTO::getStudentId).toList();
        assertThat(studentIds)
                .as("""
                    This is the assertion the old DTO failed. Rows must be \
                    distinguishable: the client builds BulkGradeRequest.studentIds \
                    from them, and a grid of identical rows cannot be rendered or \
                    submitted.""")
                .doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(fixture.studentIds);
    }

    @Test
    @DisplayName("rows come back ordered by student name, so the grid is stable between loads")
    void rowsAreOrderedByStudent() {
        List<String> names = progressService.getGradingGrid(topicId, Pagination.of(0, 50))
                .getContent().stream().map(GradingGridRowDTO::getStudentName).toList();

        assertThat(names)
                .as("an unordered grid reshuffles under the trainer between pages")
                .isSorted();
    }
}
