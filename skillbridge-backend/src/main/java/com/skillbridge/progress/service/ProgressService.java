package com.skillbridge.progress.service;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.ForbiddenException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.progress.domain.ProgressStatus;
import com.skillbridge.progress.dto.*;
import com.skillbridge.progress.entity.StudentBatchProgress;
import com.skillbridge.progress.entity.TopicProgress;
import com.skillbridge.progress.repository.StudentBatchProgressRepository;
import com.skillbridge.progress.repository.TopicProgressRepository;
import com.skillbridge.progress.repository.projection.BatchProgressAggregate;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.syllabus.entity.SyllabusModule;
import com.skillbridge.syllabus.entity.SyllabusSubmodule;
import com.skillbridge.syllabus.entity.SyllabusTopic;
import com.skillbridge.syllabus.repository.SyllabusTopicRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-student progress through a batch curriculum.
 *
 * <p>Three responsibilities, in order of how often they run:
 *
 * <ol>
 *   <li><b>Read</b> — assemble a student's progress as the curriculum tree, for
 *       the student's own view and the trainer's grading grid.</li>
 *   <li><b>Grade</b> — record a trainer's assessment, singly or in bulk.</li>
 *   <li><b>Initialise</b> — seed {@code PENDING} rows when a student enrolls, or
 *       backfill them when topics are added to a syllabus mid-course.</li>
 * </ol>
 *
 * <p>Every grading path recomputes the denormalised
 * {@link StudentBatchProgress} summary for the students it touched, so the
 * dashboard never has to aggregate raw progress rows at read time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ProgressService {

    /** Below this weighted percentage a student shows up on the at-risk report. */
    private static final BigDecimal AT_RISK_THRESHOLD = BigDecimal.valueOf(40);

    private final TopicProgressRepository progressRepository;
    private final StudentBatchProgressRepository summaryRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final BatchRepository batchRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final SyllabusTopicRepository topicRepository;

    // ------------------------------------------------------------------
    // Initialisation
    // ------------------------------------------------------------------

    /**
     * Seed a PENDING row for every topic in the batch's curriculum.
     *
     * <p>Called on enrollment. One {@code INSERT ... SELECT} covers the whole
     * syllabus, and {@code ON CONFLICT DO NOTHING} makes a repeat call harmless —
     * which matters, because enrollment approval is retryable and because this is
     * also how mid-course syllabus additions reach an existing student.
     */
    @Transactional
    public int initialiseProgress(Long studentId, Long batchId) {
        int created = progressRepository.initialiseProgressForStudent(studentId, batchId);
        log.info("Initialised {} progress rows for student {} in batch {}", created, studentId, batchId);

        if (created > 0) {
            recomputeSummary(studentId, batchId);
        }
        return created;
    }

    /**
     * Seed progress rows for every active enrollment in a batch.
     *
     * <p>Run this after adding topics to a syllabus students are partway through;
     * without it, new topics are invisible to everyone already enrolled.
     */
    @Transactional
    public int backfillBatch(Long batchId) {
        int created = progressRepository.backfillProgressForBatch(batchId);
        log.info("Backfilled {} progress rows across batch {}", created, batchId);

        if (created > 0) {
            enrollmentRepository.findByBatchId(batchId)
                    .forEach(e -> recomputeSummary(e.getStudent().getId(), batchId));
        }
        return created;
    }

    /** Discard a student's progress in a batch. Used when an enrollment is revoked. */
    @Transactional
    public void discardProgress(Long studentId, Long batchId) {
        int removed = progressRepository.deleteForStudentInBatch(studentId, batchId);
        summaryRepository.findByStudentIdAndBatchId(studentId, batchId)
                .ifPresent(summaryRepository::delete);
        log.info("Discarded {} progress rows for student {} in batch {}", removed, studentId, batchId);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    /**
     * A student's progress through one batch, shaped as the curriculum tree.
     *
     * <p>One query fetches every row with its topic, sub-module and module already
     * joined; the tree is then assembled in memory. The alternative — walking the
     * curriculum and asking for progress per topic — is an N+1 against a database
     * that is not on the same machine.
     */
    public BatchProgressDTO getStudentProgress(Long studentId, Long batchId) {
        Student student = requireStudent(studentId);
        Batch batch = requireBatch(batchId);
        requireEnrolled(studentId, batchId);

        List<TopicProgress> rows = progressRepository.findFullProgressForStudentInBatch(studentId, batchId);

        return assembleTree(student, batch, rows);
    }

    /** The same view, addressed by the logged-in student's user id. */
    public BatchProgressDTO getMyProgress(Long userId, Long batchId) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No student profile found. Complete your profile setup first."));
        return getStudentProgress(student.getId(), batchId);
    }

    /**
     * Every enrolled student's standing in a batch, for the trainer's overview.
     *
     * <p>Reads the denormalised summary table rather than aggregating raw progress
     * rows, so the cost is one indexed scan regardless of syllabus size.
     */
    public Page<StudentProgressSummaryDTO> getBatchOverview(Long batchId, Pageable pageable) {
        requireBatch(batchId);

        return withStudentNames(
                summaryRepository.findByBatchIdOrderByWeightedPercentAsc(batchId, pageable));
    }

    /** Students below the at-risk threshold, worst first. */
    public Page<StudentProgressSummaryDTO> getAtRiskStudents(Long batchId, Pageable pageable) {
        requireBatch(batchId);

        return withStudentNames(summaryRepository.findAtRisk(batchId, AT_RISK_THRESHOLD, pageable));
    }

    /**
     * Attaches student names to a page of summaries in one extra query.
     *
     * <p>The summary row stores a student id and no name, so something has to
     * resolve them. Doing it per row is one select per student; this collects
     * the ids on the page and looks them up once. Scoped to the page rather
     * than the whole result set, which is the point of paginating at all.
     */
    private Page<StudentProgressSummaryDTO> withStudentNames(Page<StudentBatchProgress> summaries) {
        if (summaries.isEmpty()) {
            return summaries.map(s -> toSummaryDto(s, null));
        }

        Map<Long, Student> students = studentRepository
                .findAllById(summaries.getContent().stream()
                        .map(StudentBatchProgress::getStudentId).toList())
                .stream()
                .collect(Collectors.toMap(Student::getId, s -> s));

        return summaries.map(s -> toSummaryDto(s, students.get(s.getStudentId())));
    }

    /** Every student's row for one topic — the grading grid. */
    public Page<GradingGridRowDTO> getGradingGrid(Long topicId, Pageable pageable) {
        SyllabusTopic topic = topicRepository.findById(topicId)
                .orElseThrow(() -> ResourceNotFoundException.of("Topic", topicId));

        return progressRepository.findGradingGridForTopic(topic.getId(), pageable)
                .map(ProgressService::toGridRow);
    }

    /**
     * A grid row, named by its student.
     *
     * <p>This used to map to {@link TopicProgressDTO}, which carries the topic
     * and not the student — so every row of the grid came back identical apart
     * from {@code progressId}. The query has always fetched the student; only
     * the mapping dropped it.
     */
    private static GradingGridRowDTO toGridRow(TopicProgress p) {
        return GradingGridRowDTO.builder()
                .progressId(p.getId())
                .studentId(p.getStudent().getId())
                .studentName(p.getStudent().getFullName())
                .rollNumber(p.getStudent().getRollNumber())
                .status(p.getStatus())
                .score(p.getScore())
                .comment(p.getComment())
                .gradedByTrainerName(p.getUpdatedBy() == null ? null : p.getUpdatedBy().getFullName())
                .completedAt(p.getCompletedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    // ------------------------------------------------------------------
    // Grading
    // ------------------------------------------------------------------

    /**
     * Record one student's assessment on one topic.
     *
     * @param trainerUserId the grading trainer's <em>user</em> id, taken from the
     *                      security context rather than the request body — a
     *                      trainer id supplied by the client is an attribution
     *                      forgery waiting to happen
     */
    @Transactional
    public TopicProgressDTO gradeTopic(Long trainerUserId, Long topicId, GradeTopicRequest request) {
        Trainer trainer = requireTrainer(trainerUserId);
        SyllabusTopic topic = requireTopic(topicId);
        Long batchId = batchIdOf(topic);

        requireTrainerAssignedToBatch(trainerUserId, batchId);
        requireEnrolled(request.getStudentId(), batchId);

        TopicProgress progress = progressRepository
                .findByStudentIdAndTopicId(request.getStudentId(), topicId)
                .orElseThrow(() -> new BusinessRuleException(
                        "PROGRESS_NOT_INITIALISED",
                        "No progress record exists for this student and topic. "
                                + "The student may have enrolled before this topic was added to the syllabus."));

        progress.record(request.getStatus(), trainer, request.getComment(), request.getScore());
        progressRepository.save(progress);

        recomputeSummary(request.getStudentId(), batchId);

        log.info("Trainer {} graded student {} on topic {} as {}",
                trainer.getId(), request.getStudentId(), topicId, request.getStatus());

        return toTopicDto(progress);
    }

    /**
     * Grade many students on one topic in a single transaction.
     *
     * <p>Loads every affected row in one query, applies the change in memory and
     * lets Hibernate flush the batch. Students who have no progress row — not
     * enrolled, or enrolled after the topic was added — are reported back rather
     * than silently ignored or allowed to fail the whole call.
     */
    @Transactional
    public BulkGradeResultDTO bulkGrade(Long trainerUserId, Long topicId, BulkGradeRequest request) {
        Trainer trainer = requireTrainer(trainerUserId);
        SyllabusTopic topic = requireTopic(topicId);
        Long batchId = batchIdOf(topic);

        requireTrainerAssignedToBatch(trainerUserId, batchId);

        List<Long> requested = request.getStudentIds().stream().distinct().toList();

        List<TopicProgress> rows =
                progressRepository.findForBulkGrading(batchId, topicId, requested);

        Set<Long> found = rows.stream()
                .map(tp -> tp.getStudent().getId())
                .collect(Collectors.toSet());

        rows.forEach(tp -> tp.record(request.getStatus(), trainer,
                request.getComment(), request.getScore()));
        progressRepository.saveAll(rows);

        // Recompute once per affected student, after all the grades have landed.
        found.forEach(studentId -> recomputeSummary(studentId, batchId));

        List<Long> skipped = requested.stream().filter(id -> !found.contains(id)).toList();

        log.info("Trainer {} bulk-graded {}/{} students on topic {} as {}",
                trainer.getId(), rows.size(), requested.size(), topicId, request.getStatus());

        return BulkGradeResultDTO.builder()
                .topicId(topicId)
                .topicName(topic.getName())
                .requested(requested.size())
                .graded(rows.size())
                .skippedStudentIds(skipped)
                .message(skipped.isEmpty()
                        ? "Graded " + rows.size() + " students."
                        : "Graded " + rows.size() + " students. " + skipped.size()
                                + " were skipped — they have no progress record for this topic.")
                .build();
    }

    // ------------------------------------------------------------------
    // Summary maintenance
    // ------------------------------------------------------------------

    /**
     * Rebuild the denormalised summary for one (student, batch) from source.
     *
     * <p>A full recompute rather than an incremental adjustment. Incremental
     * counter maintenance drifts the moment one code path forgets to call it, and
     * the drift is silent — you find out when a student complains their
     * percentage is wrong. One aggregate query cannot be wrong.
     */
    @Transactional
    public void recomputeSummary(Long studentId, Long batchId) {
        BatchProgressAggregate agg = progressRepository.aggregateForStudentInBatch(studentId, batchId);

        int total = agg == null ? 0 : agg.getTotal();

        StudentBatchProgress summary = summaryRepository
                .findByStudentIdAndBatchId(studentId, batchId)
                .orElseGet(() -> newSummary(studentId, batchId));

        if (total == 0) {
            summary.apply(0, 0, 0, 0, 0.0, null, null);
        } else {
            double weightedSum = agg.getWeightedSum() == null ? 0.0 : agg.getWeightedSum();
            summary.apply(
                    total,
                    agg.getCompleted(),
                    agg.getInProgress(),
                    agg.getNeedsWork(),
                    weightedSum / total,
                    agg.getAverageScore(),
                    agg.getLastActivityAt());
        }

        summaryRepository.save(summary);
    }

    private StudentBatchProgress newSummary(Long studentId, Long batchId) {
        Batch batch = requireBatch(batchId);
        return StudentBatchProgress.builder()
                .studentId(studentId)
                .batchId(batchId)
                .collegeId(batch.getCollege().getId())
                .build();
    }

    // ------------------------------------------------------------------
    // Tree assembly
    // ------------------------------------------------------------------

    /**
     * Group flat progress rows back into module → sub-module → topic.
     *
     * <p>The query returns rows already ordered by the three display orders, so
     * {@code LinkedHashMap} preserves curriculum order without re-sorting.
     */
    private BatchProgressDTO assembleTree(Student student, Batch batch, List<TopicProgress> rows) {

        Map<SyllabusModule, Map<SyllabusSubmodule, List<TopicProgress>>> tree = new LinkedHashMap<>();

        for (TopicProgress row : rows) {
            SyllabusSubmodule submodule = row.getTopic().getSubmodule();
            SyllabusModule module = submodule.getModule();
            tree.computeIfAbsent(module, m -> new LinkedHashMap<>())
                    .computeIfAbsent(submodule, sm -> new ArrayList<>())
                    .add(row);
        }

        List<ModuleProgressDTO> modules = new ArrayList<>();
        for (var moduleEntry : tree.entrySet()) {
            SyllabusModule module = moduleEntry.getKey();

            List<SubmoduleProgressDTO> submodules = new ArrayList<>();
            for (var submoduleEntry : moduleEntry.getValue().entrySet()) {
                SyllabusSubmodule submodule = submoduleEntry.getKey();
                List<TopicProgress> topics = submoduleEntry.getValue();

                submodules.add(SubmoduleProgressDTO.builder()
                        .submoduleId(submodule.getId())
                        .submoduleName(submodule.getName())
                        .displayOrder(submodule.getDisplayOrder())
                        .weekNumber(submodule.getWeekNumber())
                        .topicsTotal(topics.size())
                        .topicsCompleted(countBy(topics, ProgressStatus.COMPLETED))
                        .weightedPercent(weightedPercent(topics))
                        .topics(topics.stream().map(this::toTopicDto).toList())
                        .build());
            }

            List<TopicProgress> moduleTopics = moduleEntry.getValue().values().stream()
                    .flatMap(List::stream)
                    .toList();

            modules.add(ModuleProgressDTO.builder()
                    .moduleId(module.getId())
                    .moduleName(module.getName())
                    .displayOrder(module.getDisplayOrder())
                    .startDate(module.getStartDate())
                    .endDate(module.getEndDate())
                    .topicsTotal(moduleTopics.size())
                    .topicsCompleted(countBy(moduleTopics, ProgressStatus.COMPLETED))
                    .weightedPercent(weightedPercent(moduleTopics))
                    .submodules(submodules)
                    .build());
        }

        modules.sort(Comparator.comparing(ModuleProgressDTO::getDisplayOrder,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Double averageScore = rows.stream()
                .map(TopicProgress::getScore)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .stream().boxed().findFirst().orElse(null);

        LocalDateTime lastActivity = rows.stream()
                .map(TopicProgress::getUpdatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        return BatchProgressDTO.builder()
                .batchId(batch.getId())
                .batchName(batch.getName())
                .studentId(student.getId())
                .studentName(student.getFullName())
                .topicsTotal(rows.size())
                .topicsCompleted(countBy(rows, ProgressStatus.COMPLETED))
                .topicsInProgress(countBy(rows, ProgressStatus.IN_PROGRESS))
                .topicsNeedsWork(countBy(rows, ProgressStatus.NEEDS_IMPROVEMENT))
                .topicsPending(countBy(rows, ProgressStatus.PENDING))
                .weightedPercent(weightedPercent(rows))
                .averageScore(averageScore)
                .lastActivityAt(lastActivity)
                .modules(modules)
                .build();
    }

    private int countBy(List<TopicProgress> rows, ProgressStatus status) {
        return (int) rows.stream().filter(r -> r.getStatus() == status).count();
    }

    private double weightedPercent(List<TopicProgress> rows) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        double sum = rows.stream().mapToDouble(TopicProgress::weight).sum();
        return Math.round(sum / rows.size() * 10000.0) / 100.0;
    }

    // ------------------------------------------------------------------
    // Mapping
    // ------------------------------------------------------------------

    private TopicProgressDTO toTopicDto(TopicProgress p) {
        SyllabusTopic topic = p.getTopic();
        Trainer trainer = p.getUpdatedBy();

        return TopicProgressDTO.builder()
                .progressId(p.getId())
                .topicId(topic.getId())
                .topicName(topic.getName())
                .topicDescription(topic.getDescription())
                .displayOrder(topic.getDisplayOrder())
                .status(p.getStatus())
                .score(p.getScore())
                .comment(p.getComment())
                .gradedByTrainerId(trainer == null ? null : trainer.getId())
                .gradedByTrainerName(trainer == null ? null : trainer.getFullName())
                .startedAt(p.getStartedAt())
                .completedAt(p.getCompletedAt())
                .updatedAt(p.getUpdatedAt())
                .build();
    }

    private StudentProgressSummaryDTO toSummaryDto(StudentBatchProgress s, Student student) {
        return StudentProgressSummaryDTO.builder()
                .studentId(s.getStudentId())
                .studentName(student == null ? null : student.getFullName())
                .rollNumber(student == null ? null : student.getRollNumber())
                .topicsTotal(s.getTopicsTotal())
                .topicsCompleted(s.getTopicsCompleted())
                .topicsNeedsWork(s.getTopicsNeedsWork())
                .weightedPercent(s.getWeightedPercent().doubleValue())
                .averageScore(s.getAverageScore() == null ? null : s.getAverageScore().doubleValue())
                .lastActivityAt(s.getLastActivityAt())
                .atRisk(s.getWeightedPercent().compareTo(AT_RISK_THRESHOLD) < 0)
                .build();
    }

    // ------------------------------------------------------------------
    // Guards
    // ------------------------------------------------------------------

    private Student requireStudent(Long studentId) {
        return studentRepository.findById(studentId)
                .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));
    }

    private Trainer requireTrainer(Long trainerUserId) {
        return trainerRepository.findByUser_Id(trainerUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No trainer profile is linked to this account."));
    }

    private Batch requireBatch(Long batchId) {
        return batchRepository.findById(batchId)
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));
    }

    private SyllabusTopic requireTopic(Long topicId) {
        return topicRepository.findById(topicId)
                .orElseThrow(() -> ResourceNotFoundException.of("Topic", topicId));
    }

    /** Walks topic → sub-module → module to find the owning batch. */
    private Long batchIdOf(SyllabusTopic topic) {
        return topic.getSubmodule().getModule().getBatch().getId();
    }

    private void requireEnrolled(Long studentId, Long batchId) {
        if (enrollmentRepository.findByBatchIdAndStudentId(batchId, studentId).isEmpty()) {
            throw new BusinessRuleException("NOT_ENROLLED",
                    "This student is not enrolled in the batch that owns this topic.");
        }
    }

    /**
     * A trainer may only grade batches they are assigned to.
     *
     * <p>Without this check any authenticated trainer could grade any student in
     * any batch in the system — the {@code TRAINER} role alone is not
     * authorisation to touch a particular batch.
     */
    private void requireTrainerAssignedToBatch(Long trainerUserId, Long batchId) {
        if (!batchRepository.isTrainerAssignedToBatch(trainerUserId, batchId)) {
            throw new ForbiddenException("You are not assigned to this batch.");
        }
    }
}
