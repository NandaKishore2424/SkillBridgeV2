package com.skillbridge.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import com.skillbridge.common.exception.GlobalExceptionHandler;
import com.skillbridge.progress.controller.ProgressController;
import com.skillbridge.progress.domain.ProgressStatus;
import com.skillbridge.progress.dto.BulkGradeResultDTO;
import com.skillbridge.progress.dto.GradeTopicRequest;
import com.skillbridge.progress.dto.TopicProgressDTO;
import com.skillbridge.progress.service.ProgressService;
import com.skillbridge.testsupport.WebSliceSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The grading endpoints' HTTP contract: who may call them, what bodies they
 * refuse, and what they hand the service.
 *
 * <p>A slice, not an integration test. The service is mocked and there is no
 * database, so this starts in about a second and asserts only what the web layer
 * owns. What the service then does with those arguments is
 * {@code GradingGridTest}'s and {@code StudentProgressTreeTest}'s job, against a
 * real database.
 *
 * <p>Grading is worth this attention because it is the one write path a trainer
 * uses on every student, and because it has already shipped one defect that
 * answered 200 while being unusable — {@code getGradingGrid} returned rows that
 * named the topic rather than the student, so the screen could not be built.
 * That was invisible to a route-level drift check and would be invisible to a
 * test that only asserted a status code, which is why the assertions here are
 * about arguments and bodies rather than 200s.
 */
@WebMvcTest(
        controllers = ProgressController.class,
        /*
         * Exclude the application's servlet filters from the slice.
         *
         * @WebMvcTest includes every Filter bean by default, and this
         * application's filters reach a long way: TokenAuthenticationFilter
         * needs the user repository, which needs an EntityManagerFactory, which
         * a web slice does not have. Without this the context fails to start
         * with "No bean named 'entityManagerFactory' available" -- which reads
         * like a missing database and is really a filter that should not be here.
         *
         * Excluding by the Filter interface rather than by listing the four
         * classes is deliberate: a fifth filter added later is excluded
         * automatically instead of breaking this test for a reason unrelated to
         * it. The filter chain is covered by SecurityFilterOrderTest,
         * CorrelationIdFilterTest, RateLimitingFilterTest and TokenRevocationTest.
         */
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = Filter.class))
@Import({WebSliceSupport.class, GlobalExceptionHandler.class})
class ProgressControllerSliceTest {

    private static final String GRADE_ONE = "/api/v1/trainer/topics/{topicId}/progress";
    private static final String GRADE_BULK = "/api/v1/trainer/topics/{topicId}/progress/bulk";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private ProgressService progressService;

    /**
     * Not used by any assertion here, and required anyway.
     *
     * <p>{@code @WebMvcTest} loads {@code WebMvcConfigurer} beans, which pulls in
     * {@code IdempotencyWebConfig} and with it {@code IdempotencyInterceptor},
     * whose service reaches the database. Mocking the service is better than
     * excluding the interceptor: the interceptor is genuinely part of the web
     * layer, and leaving it in the chain means these requests traverse the same
     * path a real one does. Its own behaviour is covered by
     * {@code IdempotencyContractTest} against a real database, and by
     * {@code IdempotencyRulesTest} in the fast tier.
     */
    @MockitoBean
    private com.skillbridge.common.idempotency.IdempotencyService idempotencyService;

    // ---------------------------------------------------------------- who

    @Test
    @DisplayName("a trainer may grade")
    void trainerMayGrade() throws Exception {
        when(progressService.gradeTopic(anyLong(), anyLong(), any()))
                .thenReturn(TopicProgressDTO.builder().build());

        mvc.perform(put(GRADE_ONE, 5L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9L, ProgressStatus.COMPLETED, 85)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a student may not grade, and the service is never reached")
    void studentMayNotGrade() throws Exception {
        mvc.perform(put(GRADE_ONE, 5L)
                        .with(WebSliceSupport.student(9L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9L, ProgressStatus.COMPLETED, 100)))
                .andExpect(status().isForbidden());

        // The status alone is not enough. A 403 raised after the service ran
        // would still have written the grade.
        verifyNoInteractions(progressService);
    }

    @Test
    @DisplayName("a college admin may not grade either: reading a batch is not marking it")
    void collegeAdminMayNotGrade() throws Exception {
        // COLLEGE_ADMIN can read the batch overview and the grading grid, which
        // makes it easy to assume they can also write. @PreAuthorize on this
        // method says TRAINER only, and that distinction is the test.
        mvc.perform(put(GRADE_ONE, 5L)
                        .with(WebSliceSupport.collegeAdmin(3L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9L, ProgressStatus.COMPLETED, 70)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(progressService);
    }

    // ------------------------------------------------------- whose identity

    @Test
    @DisplayName("the grading trainer comes from the token, never from the body")
    void graderIdentityComesFromTheToken() throws Exception {
        when(progressService.gradeTopic(anyLong(), anyLong(), any()))
                .thenReturn(TopicProgressDTO.builder().build());

        // A body that tries to nominate a different grader. Nothing in the DTO
        // reads it, and this test is what keeps that true: adding a
        // `trainerId` field and honouring it would let any trainer attribute a
        // grade to a colleague.
        String spoofed = """
                {"studentId":9,"status":"COMPLETED","score":85,"trainerId":999,"userId":999}
                """;

        mvc.perform(put(GRADE_ONE, 5L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(spoofed))
                .andExpect(status().isOk());

        verify(progressService).gradeTopic(eq(42L), eq(5L), any());
    }

    @Test
    @DisplayName("the topic graded is the one in the path")
    void topicComesFromThePath() throws Exception {
        when(progressService.gradeTopic(anyLong(), anyLong(), any()))
                .thenReturn(TopicProgressDTO.builder().build());

        mvc.perform(put(GRADE_ONE, 7L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(9L, ProgressStatus.IN_PROGRESS, null)))
                .andExpect(status().isOk());

        verify(progressService).gradeTopic(eq(42L), eq(7L), any());
    }

    // ------------------------------------------------------------ what body

    @Test
    @DisplayName("a missing student id is rejected before the service is called")
    void studentIdIsRequired() throws Exception {
        mvc.perform(put(GRADE_ONE, 5L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"COMPLETED","score":85}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.studentId").exists());

        verifyNoInteractions(progressService);
    }

    @Test
    @DisplayName("a missing status is rejected: a grade with no outcome is not a grade")
    void statusIsRequired() throws Exception {
        mvc.perform(put(GRADE_ONE, 5L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":9,"score":85}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.status").exists());

        verifyNoInteractions(progressService);
    }

    @Test
    @DisplayName("a score outside 0..100 is rejected at both ends")
    void scoreIsBounded() throws Exception {
        for (int score : new int[]{-1, 101}) {
            mvc.perform(put(GRADE_ONE, 5L)
                            .with(WebSliceSupport.trainer(42L))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(9L, ProgressStatus.COMPLETED, score)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.score").exists());
        }

        // The database has chk_progress_score saying the same thing. Both are
        // wanted: the constraint is the guarantee, this is the error message.
        verifyNoInteractions(progressService);
    }

    @Test
    @DisplayName("the boundary scores 0 and 100 are accepted")
    void scoreBoundariesAreInclusive() throws Exception {
        when(progressService.gradeTopic(anyLong(), anyLong(), any()))
                .thenReturn(TopicProgressDTO.builder().build());

        for (int score : new int[]{0, 100}) {
            mvc.perform(put(GRADE_ONE, 5L)
                            .with(WebSliceSupport.trainer(42L))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(9L, ProgressStatus.COMPLETED, score)))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("an unknown status is a 400, not a 500")
    void unparseableStatusIsABadRequest() throws Exception {
        // Jackson cannot map this to the enum. Left unhandled that is a 500,
        // which tells a client their server is broken rather than their request.
        mvc.perform(put(GRADE_ONE, 5L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"studentId":9,"status":"BRILLIANT"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(progressService);
    }

    @Test
    @DisplayName("a bulk grade of more than 500 students is refused")
    void bulkIsCapped() throws Exception {
        List<Long> tooMany = IntStream.rangeClosed(1, 501).mapToObj(Long::valueOf).toList();

        mvc.perform(put(GRADE_BULK, 5L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "studentIds", tooMany,
                                "status", "COMPLETED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.studentIds").exists());

        verify(progressService, never()).bulkGrade(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("a bulk grade passes the student list through unchanged")
    void bulkPassesTheListThrough() throws Exception {
        when(progressService.bulkGrade(anyLong(), anyLong(), any()))
                .thenReturn(BulkGradeResultDTO.builder().build());

        mvc.perform(put(GRADE_BULK, 5L)
                        .with(WebSliceSupport.trainer(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of(
                                "studentIds", List.of(11L, 12L, 13L),
                                "status", "NEEDS_IMPROVEMENT"))))
                .andExpect(status().isOk());

        ArgumentCaptor<com.skillbridge.progress.dto.BulkGradeRequest> captured =
                ArgumentCaptor.forClass(com.skillbridge.progress.dto.BulkGradeRequest.class);
        verify(progressService).bulkGrade(eq(42L), eq(5L), captured.capture());

        // Order and membership both: the grid sends the rows the trainer ticked,
        // and silently dropping or reordering one grades the wrong student.
        assertThat(captured.getValue().getStudentIds()).containsExactly(11L, 12L, 13L);
        assertThat(captured.getValue().getStatus()).isEqualTo(ProgressStatus.NEEDS_IMPROVEMENT);
    }

    // ---------------------------------------------------------------- utils

    private String body(Long studentId, ProgressStatus status, Integer score) throws Exception {
        GradeTopicRequest request = new GradeTopicRequest();
        request.setStudentId(studentId);
        request.setStatus(status);
        request.setScore(score);
        return json.writeValueAsString(request);
    }
}
