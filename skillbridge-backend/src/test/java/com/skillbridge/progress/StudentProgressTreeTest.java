package com.skillbridge.progress;

import com.skillbridge.progress.domain.ProgressStatus;
import com.skillbridge.progress.dto.BatchProgressDTO;
import com.skillbridge.progress.dto.BulkGradeRequest;
import com.skillbridge.progress.dto.ModuleProgressDTO;
import com.skillbridge.progress.dto.SubmoduleProgressDTO;
import com.skillbridge.progress.dto.TopicProgressDTO;
import com.skillbridge.progress.service.ProgressService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The student's progress tree has to be renderable, and its totals have to
 * agree with the rows underneath them.
 *
 * <p>The companion to {@link GradingGridTest}, and it guards the same class of
 * defect from the other side. The grading grid answered 200 for a whole phase
 * with rows that named the topic instead of the student, so no screen could be
 * built against it — nothing caught that, because the drift check compares
 * routes and no test asserted that two rows differed.
 *
 * <p>This endpoint is more exposed to that failure, not less, because it returns
 * a <em>tree</em>. A flat list that loses a row is short; a tree that loses one
 * still looks like a tree. And every level carries its own rollup, so there are
 * three more ways to answer 200 with something wrong: a module can report eight
 * topics and hand back four, and the page will cheerfully print both numbers.
 *
 * <p>So the assertions here are deliberately about <em>agreement</em> — the
 * headline counts against the leaves, at all three levels — rather than about
 * any particular number being right in isolation.
 */
@SpringBootTest
@IntegrationTest
class StudentProgressTreeTest {

    private static final String COLLEGE_CODE = "PROGRESSTREE";

    @Autowired private ProgressService progressService;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;
    private Long batchId;
    private Long studentId;
    private Long studentUserId;

    /** Two modules, so the fixture has siblings at every level: 2 x 2 x 2 topics. */
    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(1, 2, 2);
        batchId = fixture.batchIds.get(0);
        studentId = fixture.studentIds.get(0);
        studentUserId = fixture.studentUserIds.get(0);

        // The fixture enrols with raw SQL, which does not go through the path
        // that seeds progress rows. backfillBatch is the production code for
        // exactly that gap.
        assertThat(progressService.backfillBatch(batchId))
                .as("with no progress rows every count below would be 0 == 0, and pass for the wrong reason")
                .isPositive();

        // Grade two of the eight topics differently. An all-PENDING tree makes
        // every count assertion vacuous: 0 completed does equal 0 completed.
        grade(topicIds().get(0), ProgressStatus.COMPLETED, 90);
        grade(topicIds().get(1), ProgressStatus.NEEDS_IMPROVEMENT, 40);
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
    }

    @Test
    @DisplayName("the tree holds every topic in the curriculum, and each one can be labelled")
    void treeIsCompleteAndRenderable() {
        BatchProgressDTO tree = progressService.getStudentProgress(studentId, batchId);

        assertThat(tree.getModules())
                .as("the fixture seeded two modules")
                .hasSize(2);

        List<TopicProgressDTO> leaves = leavesOf(tree);
        assertThat(leaves)
                .as("2 modules x 2 sub-modules x 2 topics — a tree that drops a branch still looks like a tree")
                .hasSize(curriculumTopicCount());

        assertThat(tree.getModules()).allSatisfy(module -> {
            assertThat(module.getModuleId()).isNotNull();
            assertThat(module.getModuleName()).isNotBlank();
            assertThat(module.getSubmodules()).isNotEmpty().allSatisfy(submodule -> {
                assertThat(submodule.getSubmoduleId()).isNotNull();
                assertThat(submodule.getSubmoduleName()).isNotBlank();
                assertThat(submodule.getTopics()).isNotEmpty();
            });
        });

        assertThat(leaves).allSatisfy(topic -> {
            assertThat(topic.getTopicId()).isNotNull();
            assertThat(topic.getTopicName()).isNotBlank();
            assertThat(topic.getStatus())
                    .as("a null status has no pill to render and no row to sort")
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("every headline count agrees with the rows beneath it, at all three levels")
    void rollupsAgreeWithLeaves() {
        BatchProgressDTO tree = progressService.getStudentProgress(studentId, batchId);
        List<TopicProgressDTO> leaves = leavesOf(tree);

        assertThat(tree.getTopicsTotal()).isEqualTo(leaves.size());
        assertThat(tree.getTopicsCompleted()).isEqualTo(count(leaves, ProgressStatus.COMPLETED));
        assertThat(tree.getTopicsInProgress()).isEqualTo(count(leaves, ProgressStatus.IN_PROGRESS));
        assertThat(tree.getTopicsNeedsWork()).isEqualTo(count(leaves, ProgressStatus.NEEDS_IMPROVEMENT));
        assertThat(tree.getTopicsPending()).isEqualTo(count(leaves, ProgressStatus.PENDING));

        assertThat(tree.getTopicsCompleted() + tree.getTopicsInProgress()
                + tree.getTopicsNeedsWork() + tree.getTopicsPending())
                .as("the four status counts partition the topics; a leftover is a status the page cannot show")
                .isEqualTo(tree.getTopicsTotal());

        // setUp graded one COMPLETED and one NEEDS_IMPROVEMENT, so these are not
        // 0 == 0. Without a graded row the assertions above hold for a tree
        // where nothing has ever been counted.
        assertThat(tree.getTopicsCompleted()).isOne();
        assertThat(tree.getTopicsNeedsWork()).isOne();
        assertThat(tree.getAverageScore())
                .as("the mean of the two graded scores, 90 and 40")
                .isEqualTo(65.0);

        for (ModuleProgressDTO module : tree.getModules()) {
            List<TopicProgressDTO> moduleLeaves = module.getSubmodules().stream()
                    .flatMap(sm -> sm.getTopics().stream()).toList();

            assertThat(module.getTopicsTotal())
                    .as("module '%s' reports a total the page cannot show", module.getModuleName())
                    .isEqualTo(moduleLeaves.size());
            assertThat(module.getTopicsCompleted())
                    .isEqualTo(count(moduleLeaves, ProgressStatus.COMPLETED));

            for (SubmoduleProgressDTO submodule : module.getSubmodules()) {
                assertThat(submodule.getTopicsTotal()).isEqualTo(submodule.getTopics().size());
                assertThat(submodule.getTopicsCompleted())
                        .isEqualTo(count(submodule.getTopics(), ProgressStatus.COMPLETED));
            }
        }
    }

    @Test
    @DisplayName("the weighted percentage is the weights of the rows on screen, not a completed count")
    void weightedPercentIsDerivedFromTheSameRows() {
        BatchProgressDTO tree = progressService.getStudentProgress(studentId, batchId);
        List<TopicProgressDTO> leaves = leavesOf(tree);

        double expected = leaves.stream().mapToDouble(t -> t.getStatus().weight()).sum()
                / leaves.size() * 100.0;

        assertThat(tree.getWeightedPercent())
                .as("""
                    The client renders this number and the rows together, so they \
                    have to come from the same rows. It is also why the client \
                    must not recompute it: 1 completed of 8 is 12.5%, but one \
                    completed and one needing work is 15.6%.""")
                .isEqualTo(expected, org.assertj.core.data.Offset.offset(0.01));

        assertThat(tree.getWeightedPercent())
                .as("a graded tree sitting at zero would mean the weights never reached the total")
                .isPositive();
    }

    @Test
    @DisplayName("the tree comes back in curriculum order, so it does not reshuffle between loads")
    void treeIsInCurriculumOrder() {
        BatchProgressDTO tree = progressService.getStudentProgress(studentId, batchId);

        assertThat(tree.getModules())
                .extracting(ModuleProgressDTO::getDisplayOrder)
                .isSortedAccordingTo(Comparator.nullsLast(Comparator.naturalOrder()));

        for (ModuleProgressDTO module : tree.getModules()) {
            assertThat(module.getSubmodules())
                    .as("sub-modules of '%s' are out of order", module.getModuleName())
                    .extracting(SubmoduleProgressDTO::getDisplayOrder)
                    .isSortedAccordingTo(Comparator.nullsLast(Comparator.naturalOrder()));

            for (SubmoduleProgressDTO submodule : module.getSubmodules()) {
                assertThat(submodule.getTopics())
                        .as("topics of '%s' are out of order", submodule.getSubmoduleName())
                        .extracting(TopicProgressDTO::getDisplayOrder)
                        .isSortedAccordingTo(Comparator.nullsLast(Comparator.naturalOrder()));
            }
        }
    }

    @Test
    @DisplayName("the student-facing path resolves the caller's own tree from their user id")
    void myProgressResolvesTheSignedInStudent() {
        // What the controller actually calls. The path carries no student id, so
        // this hop from user to student is the whole of the authorisation: get it
        // wrong and a student reads someone else's grades.
        BatchProgressDTO mine = progressService.getMyProgress(studentUserId, batchId);

        assertThat(mine.getStudentId()).isEqualTo(studentId);
        assertThat(mine.getStudentName()).isNotBlank();
        assertThat(leavesOf(mine))
                .extracting(TopicProgressDTO::getProgressId)
                .containsExactlyElementsOf(
                        leavesOf(progressService.getStudentProgress(studentId, batchId)).stream()
                                .map(TopicProgressDTO::getProgressId).toList());
    }

    // ------------------------------------------------------------------

    private static List<TopicProgressDTO> leavesOf(BatchProgressDTO tree) {
        return tree.getModules().stream()
                .flatMap(m -> m.getSubmodules().stream())
                .flatMap(sm -> sm.getTopics().stream())
                .toList();
    }

    private static int count(List<TopicProgressDTO> topics, ProgressStatus status) {
        return (int) topics.stream().filter(t -> t.getStatus() == status).count();
    }

    /** Read from the curriculum itself, not from the tree the tree is meant to prove. */
    private int curriculumTopicCount() {
        return jdbc.queryForObject("""
                SELECT count(*) FROM syllabus_topics t
                JOIN syllabus_submodules sm ON sm.id = t.submodule_id
                JOIN syllabus_modules m ON m.id = sm.module_id
                WHERE m.batch_id = ?
                """, Integer.class, batchId);
    }

    private List<Long> topicIds() {
        return jdbc.queryForList("""
                SELECT t.id FROM syllabus_topics t
                JOIN syllabus_submodules sm ON sm.id = t.submodule_id
                JOIN syllabus_modules m ON m.id = sm.module_id
                WHERE m.batch_id = ?
                ORDER BY m.display_order, sm.display_order, t.display_order, t.id
                """, Long.class, batchId);
    }

    /** Through the real grading path, so the rows look the way production makes them. */
    private void grade(Long topicId, ProgressStatus status, int score) {
        BulkGradeRequest request = new BulkGradeRequest();
        request.setStudentIds(List.of(studentId));
        request.setStatus(status);
        request.setScore(score);
        request.setComment("fixture feedback");

        assertThat(progressService.bulkGrade(fixture.trainerUserId, topicId, request).getGraded())
                .as("the fixture's grading did not land, so the tree under test is all PENDING")
                .isOne();
    }
}
