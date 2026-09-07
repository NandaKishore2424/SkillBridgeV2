package com.skillbridge.student.service;

import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.enrollment.entity.Enrollment;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.enrollment.repository.projection.StudentStatsProjection;
import com.skillbridge.progress.entity.StudentBatchProgress;
import com.skillbridge.progress.entity.TopicProgress;
import com.skillbridge.progress.repository.StudentBatchProgressRepository;
import com.skillbridge.progress.repository.TopicProgressRepository;
import com.skillbridge.student.dto.*;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read model behind the student dashboard.
 *
 * <p>Every method in this class previously returned a placeholder — hardcoded
 * zeroes for the statistics, empty lists for batches and recommendations. The
 * data existed; nothing read it.
 *
 * <p>The recurring concern throughout is query count. This is the most-visited
 * screen in the product, and the obvious implementation of each method is an
 * N+1: one query for the enrollments, then one per batch for its progress, its
 * trainers, its student count. Each method below notes how it avoids that.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class StudentDashboardService {

    private static final int RECOMMENDATION_LIMIT = 6;

    private final StudentRepository studentRepository;
    private final BatchRepository batchRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final TopicProgressRepository progressRepository;
    private final StudentBatchProgressRepository summaryRepository;
    private final BatchRecommendationService recommendationService;

    /**
     * Every counter on the dashboard, from one round trip.
     *
     * <p>The naive version is five separate counts. At roughly 30ms to a hosted
     * database that is 150ms of latency before any rendering happens, on the page
     * every student opens first.
     */
    public StudentDashboardStatsDTO getDashboardStats(Long userId) {
        Student student = requireStudent(userId);

        StudentStatsProjection stats = enrollmentRepository.aggregateStatsForStudent(student.getId());

        if (stats == null) {
            return emptyStats();
        }

        return StudentDashboardStatsDTO.builder()
                .enrolledBatches(stats.getTotalEnrolled())
                .activeBatches(stats.getActiveCount())
                .completedBatches(stats.getCompletedCount())
                .upcomingBatches(stats.getUpcomingCount())
                .pendingApplications(stats.getPendingRequests())
                .totalTopicsCompleted(stats.getTopicsCompleted())
                .totalTopicsAssigned(stats.getTopicsAssigned())
                .overallProgressPercent(percent(stats.getTopicsCompleted(), stats.getTopicsAssigned()))
                .build();
    }

    /**
     * The batches this student is enrolled in, each with its progress.
     *
     * <p>Two queries regardless of how many batches there are: one fetch-joined
     * read of the enrollments, and one read of the pre-aggregated progress
     * summaries keyed by batch. Asking for progress per batch inside the mapping
     * loop would be an N+1 on exactly the screen where it hurts most.
     */
    public Page<StudentBatchDTO> getStudentBatches(Long userId, Pageable pageable) {
        Student student = requireStudent(userId);

        Page<Enrollment> enrollments =
                enrollmentRepository.findAllWithBatchDetailsByStudentId(student.getId(), pageable);

        if (enrollments.isEmpty()) {
            return enrollments.map(e -> toStudentBatchDto(e, null));
        }

        // Progress summaries for the batches on THIS page, in one query. Scoping
        // it to the page rather than to every enrollment is the whole point of
        // paginating: otherwise the cheap query stays proportional to the
        // student's entire history.
        List<Long> batchIds = enrollments.getContent().stream()
                .map(e -> e.getBatch().getId())
                .toList();

        Map<Long, StudentBatchProgress> progressByBatch = summaryRepository
                .findByStudentIdAndBatchIdIn(student.getId(), batchIds).stream()
                .collect(Collectors.toMap(StudentBatchProgress::getBatchId, Function.identity()));

        return enrollments.map(e -> toStudentBatchDto(e, progressByBatch.get(e.getBatch().getId())));
    }

    /**
     * Batches open to this student, excluding ones they are already in.
     */
    public Page<BatchDTO> getAvailableBatches(Long collegeId, Pageable pageable) {
        if (collegeId == null) {
            return Page.empty(pageable);
        }
        return batchRepository.findAvailableForCollege(collegeId, pageable).map(this::toBatchDto);
    }

    /**
     * Scored recommendations, each with a reason.
     *
     * <p>Delegated to {@link BatchRecommendationService}; see that class for why
     * the scoring is a transparent heuristic rather than a model.
     *
     * <p>Deliberately not paged. The result is capped at
     * {@link #RECOMMENDATION_LIMIT} by construction, so it is bounded already —
     * a pager over a top-six list is a control nobody would use, and the
     * ranking is the point. If the limit ever becomes a client parameter this
     * has to be revisited.
     */
    public List<RecommendedBatchDTO> getRecommendedBatches(Long userId) {
        Student student = requireStudent(userId);
        return recommendationService.recommend(student, RECOMMENDATION_LIMIT);
    }

    /** One enrolled batch in detail. */
    public StudentBatchDTO getBatchDetails(Long userId, Long batchId) {
        Student student = requireStudent(userId);

        Enrollment enrollment = enrollmentRepository
                .findByBatchIdAndStudentId(batchId, student.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "You are not enrolled in this batch."));

        StudentBatchProgress summary = summaryRepository
                .findByStudentIdAndBatchId(student.getId(), batchId)
                .orElse(null);

        return toStudentBatchDto(enrollment, summary);
    }

    /**
     * Flat per-topic progress for one batch.
     *
     * <p>Kept for the existing client contract. The richer tree-shaped view lives
     * on {@code ProgressController} at {@code /progress/detail}, which is what new
     * UI should use.
     */
    public StudentProgressDTO getStudentProgress(Long userId, Long batchId) {
        Student student = requireStudent(userId);

        Batch batch = batchRepository.findByIdAndDeletedAtIsNull(batchId)
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));

        if (enrollmentRepository.findByBatchIdAndStudentId(batchId, student.getId()).isEmpty()) {
            throw new ResourceNotFoundException("You are not enrolled in this batch.");
        }

        List<TopicProgress> rows =
                progressRepository.findFullProgressForStudentInBatch(student.getId(), batchId);

        List<StudentProgressDTO.TopicProgress> topics = rows.stream()
                .map(p -> StudentProgressDTO.TopicProgress.builder()
                        .id(p.getTopic().getId())
                        .title(p.getTopic().getName())
                        .description(p.getTopic().getDescription())
                        .status(p.getStatus().name())
                        .feedback(p.getComment())
                        .updatedAt(p.getUpdatedAt())
                        .build())
                .toList();

        return StudentProgressDTO.builder()
                .batchId(batch.getId())
                .batchName(batch.getName())
                .topics(topics)
                .build();
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private StudentBatchDTO toStudentBatchDto(Enrollment enrollment, StudentBatchProgress summary) {
        Batch batch = enrollment.getBatch();

        StudentBatchDTO dto = new StudentBatchDTO();
        dto.setId(batch.getId());
        dto.setName(batch.getName());
        dto.setDescription(batch.getDescription());
        dto.setStatus(batch.getStatus());
        dto.setStartDate(batch.getStartDate());
        dto.setEndDate(batch.getEndDate());
        dto.setCreatedAt(batch.getCreatedAt());
        dto.setUpdatedAt(batch.getUpdatedAt());
        dto.setCollegeId(batch.getCollege() == null ? null : batch.getCollege().getId());
        dto.setCollegeName(batch.getCollege() == null ? null : batch.getCollege().getName());
        dto.setEnrolledAt(enrollment.getEnrolledAt());

        dto.setTrainers(batch.getTrainers().stream()
                .map(this::toTrainerInfo)
                .toList());

        dto.setCompanies(batch.getCompanies().stream()
                .map(c -> StudentBatchDTO.CompanyInfo.builder()
                        .id(c.getId())
                        .name(c.getName())
                        .build())
                .toList());

        dto.setTrainerCount(batch.getTrainers().size());
        dto.setCompanyCount(batch.getCompanies().size());
        dto.setProgress(toProgressInfo(summary));

        return dto;
    }

    private StudentBatchDTO.TrainerInfo toTrainerInfo(Trainer trainer) {
        return StudentBatchDTO.TrainerInfo.builder()
                .id(trainer.getId())
                .fullName(trainer.getFullName())
                .build();
    }

    /**
     * Progress panel for one batch.
     *
     * <p>A student with no summary row yet — enrolled, but the batch has no
     * syllabus — gets zeroes rather than null, so the UI has nothing to
     * special-case.
     */
    private StudentBatchDTO.ProgressInfo toProgressInfo(StudentBatchProgress summary) {
        if (summary == null) {
            return StudentBatchDTO.ProgressInfo.builder()
                    .totalTopics(0)
                    .completedTopics(0)
                    .inProgressTopics(0)
                    .pendingTopics(0)
                    .completionPercentage(0.0)
                    .build();
        }

        int pending = summary.getTopicsTotal()
                - summary.getTopicsCompleted()
                - summary.getTopicsInProgress()
                - summary.getTopicsNeedsWork();

        return StudentBatchDTO.ProgressInfo.builder()
                .totalTopics(summary.getTopicsTotal())
                .completedTopics(summary.getTopicsCompleted())
                .inProgressTopics(summary.getTopicsInProgress())
                .pendingTopics(Math.max(0, pending))
                .completionPercentage(summary.getWeightedPercent().doubleValue())
                .build();
    }

    private BatchDTO toBatchDto(Batch batch) {
        return BatchDTO.builder()
                .id(batch.getId())
                .name(batch.getName())
                .description(batch.getDescription())
                .status(batch.getStatus())
                .startDate(batch.getStartDate())
                .endDate(batch.getEndDate())
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .collegeId(batch.getCollege() == null ? null : batch.getCollege().getId())
                .build();
    }

    // ------------------------------------------------------------------

    private Student requireStudent(Long userId) {
        return studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No student profile found. Complete your profile setup first."));
    }

    private StudentDashboardStatsDTO emptyStats() {
        return StudentDashboardStatsDTO.builder()
                .enrolledBatches(0).activeBatches(0).completedBatches(0).upcomingBatches(0)
                .pendingApplications(0).totalTopicsCompleted(0).totalTopicsAssigned(0)
                .overallProgressPercent(0)
                .build();
    }

    /** Guards the zero-topic case, which is otherwise a division by zero. */
    private int percent(int done, int total) {
        return total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
    }
}
