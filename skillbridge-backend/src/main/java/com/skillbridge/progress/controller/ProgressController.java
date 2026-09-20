package com.skillbridge.progress.controller;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.progress.dto.BatchProgressDTO;
import com.skillbridge.progress.dto.BulkGradeRequest;
import com.skillbridge.progress.dto.BulkGradeResultDTO;
import com.skillbridge.progress.dto.GradeTopicRequest;
import com.skillbridge.progress.dto.StudentProgressSummaryDTO;
import com.skillbridge.progress.dto.GradingGridRowDTO;
import com.skillbridge.progress.dto.TopicProgressDTO;
import com.skillbridge.progress.service.ProgressService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.StaffOnly;
import com.skillbridge.common.security.StudentOnly;
import com.skillbridge.common.security.TrainerOnly;
import com.skillbridge.common.security.TrainerOrCollegeAdmin;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Progress tracking endpoints.
 *
 * <p>Split by audience rather than by entity: a student reads their own progress
 * and can read nothing else, a trainer grades and reads their batches. The
 * student-facing paths never take a student id — it comes from the token, so
 * there is no id for anyone to tamper with.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class ProgressController {

    private final ProgressService progressService;

    // ------------------------------------------------------------------
    // Student
    // ------------------------------------------------------------------

    /** The signed-in student's progress through one batch. */
    @GetMapping("/student/batches/{batchId}/progress/detail")
    @StudentOnly
    public ResponseEntity<BatchProgressDTO> getMyProgress(@PathVariable Long batchId) {
        AuthenticatedUser user = SecurityUtils.currentUser();
        return ResponseEntity.ok(progressService.getMyProgress(user.getId(), batchId));
    }

    // ------------------------------------------------------------------
    // Trainer — reads
    // ------------------------------------------------------------------

    /**
     * Every enrolled student's standing in a batch, weakest first.
     *
     * <p>Paged: one row per enrolled student, so this grows with the batch.
     */
    @GetMapping("/trainer/batches/{batchId}/progress")
    @StaffOnly
    public ResponseEntity<PagedResponse<StudentProgressSummaryDTO>> getBatchOverview(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(
                progressService.getBatchOverview(batchId, Pagination.of(page, size))));
    }

    /** Students below the at-risk threshold. */
    @GetMapping("/trainer/batches/{batchId}/progress/at-risk")
    @StaffOnly
    public ResponseEntity<PagedResponse<StudentProgressSummaryDTO>> getAtRisk(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(
                progressService.getAtRiskStudents(batchId, Pagination.of(page, size))));
    }

    /** One student's full tree, for a trainer reviewing them. */
    @GetMapping("/trainer/batches/{batchId}/students/{studentId}/progress")
    @StaffOnly
    public ResponseEntity<BatchProgressDTO> getStudentProgress(@PathVariable Long batchId,
                                                                @PathVariable Long studentId) {
        return ResponseEntity.ok(progressService.getStudentProgress(studentId, batchId));
    }

    /**
     * The grading grid: every student's row for one topic.
     *
     * <p>Paged for the same reason as the overview — a row per enrolled
     * student. A trainer grading a class of 400 gets 20 rows at a time.
     */
    @GetMapping("/trainer/topics/{topicId}/progress")
    @StaffOnly
    public ResponseEntity<PagedResponse<GradingGridRowDTO>> getGradingGrid(
            @PathVariable Long topicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(
                progressService.getGradingGrid(topicId, Pagination.of(page, size))));
    }

    // ------------------------------------------------------------------
    // Trainer — writes
    // ------------------------------------------------------------------

    /**
     * Grade one student on one topic.
     *
     * <p>The grading trainer is taken from the token, never from the body.
     */
    @PutMapping("/trainer/topics/{topicId}/progress")
    @TrainerOnly
    public ResponseEntity<TopicProgressDTO> gradeTopic(@PathVariable Long topicId,
                                                        @Valid @RequestBody GradeTopicRequest request) {
        AuthenticatedUser user = SecurityUtils.currentUser();
        return ResponseEntity.ok(progressService.gradeTopic(user.getId(), topicId, request));
    }

    /** Grade a whole class on one topic in one transaction. */
    @PutMapping("/trainer/topics/{topicId}/progress/bulk")
    @TrainerOnly
    public ResponseEntity<BulkGradeResultDTO> bulkGrade(@PathVariable Long topicId,
                                                         @Valid @RequestBody BulkGradeRequest request) {
        AuthenticatedUser user = SecurityUtils.currentUser();
        return ResponseEntity.ok(progressService.bulkGrade(user.getId(), topicId, request));
    }

    /**
     * Seed progress rows for topics added to a syllabus after students enrolled.
     *
     * <p>Without this, a topic added mid-course is invisible to everyone already
     * on the batch.
     */
    @PostMapping("/trainer/batches/{batchId}/progress/backfill")
    @TrainerOrCollegeAdmin
    public ResponseEntity<BulkGradeResultDTO> backfill(@PathVariable Long batchId) {
        int created = progressService.backfillBatch(batchId);
        return ResponseEntity.ok(BulkGradeResultDTO.builder()
                .requested(created)
                .graded(created)
                .skippedStudentIds(List.of())
                .message("Created " + created + " new progress records.")
                .build());
    }
}
