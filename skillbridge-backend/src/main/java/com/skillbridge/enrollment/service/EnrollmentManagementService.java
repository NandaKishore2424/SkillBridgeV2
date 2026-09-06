package com.skillbridge.enrollment.service;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.exception.BadRequestException;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.audit.AuditAction;
import com.skillbridge.common.audit.AuditLogService;
import com.skillbridge.common.tenant.TenantGuard;
import com.skillbridge.enrollment.domain.EnrollmentState;
import com.skillbridge.enrollment.domain.EnrollmentStatus;
import com.skillbridge.enrollment.domain.RequestSource;
import com.skillbridge.enrollment.dto.*;
import com.skillbridge.enrollment.entity.Enrollment;
import com.skillbridge.enrollment.entity.EnrollmentRequest;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.enrollment.repository.EnrollmentRequestRepository;
import com.skillbridge.progress.service.ProgressService;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Admin and trainer side of enrollment: direct enrollment, and the review queue
 * for requests raised by trainers or students.
 *
 * <p>Three things changed here beyond the rename to {@link EnrollmentStatus}.
 * Approval now goes through the state machine rather than an unguarded setter,
 * so approving an already-rejected request is a 409 instead of a duplicate
 * enrollment. Every path that creates or removes an enrollment now also seeds or
 * discards the student's progress rows — previously a student could be enrolled
 * in a batch and have nothing to be graded on. And the DTO mapper tolerates a
 * null trainer, which a student application always has.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class EnrollmentManagementService {

    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentRequestRepository requestRepository;
    private final BatchRepository batchRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final UserRepository userRepository;
    private final ProgressService progressService;
    private final AuditLogService auditLogService;

    /** All enrollments for a batch. */
    @Transactional(readOnly = true)
    public BatchEnrollmentDTO getBatchEnrollments(Long batchId) {
        Batch batch = requireBatch(batchId);

        List<Enrollment> enrollments = enrollmentRepository.findByBatchId(batchId);

        List<EnrolledStudentDTO> students = enrollments.stream()
                .map(e -> convertToEnrolledStudentDTO(e.getStudent()))
                .collect(Collectors.toList());

        return BatchEnrollmentDTO.builder()
                .batchId(batch.getId())
                .batchName(batch.getName())
                .enrolledStudents(students)
                .enrolledCount(students.size())
                .build();
    }

    /**
     * Enroll a student directly, bypassing the request queue.
     *
     * <p>Seeds progress rows as part of the same transaction. An enrollment
     * without them is a student the trainer cannot grade.
     */
    public EnrolledStudentDTO enrollStudent(Long batchId, Long studentId) {
        Batch batch = requireBatch(batchId);
        Student student = requireStudent(studentId);

        requireSameCollege(student, batch);

        Optional<Enrollment> existing = enrollmentRepository.findByBatchIdAndStudentId(batchId, studentId);
        if (existing.isPresent()) {
            throw new ConflictException("ALREADY_ENROLLED", "Student is already enrolled in this batch");
        }

        Enrollment enrollment = Enrollment.builder()
                .batch(batch)
                .student(student)
                .collegeId(batch.getCollege().getId())
                .status(EnrollmentState.ACTIVE)
                .build();

        enrollmentRepository.save(enrollment);
        progressService.initialiseProgress(studentId, batchId);

        log.info("Enrolled student {} in batch {}", studentId, batchId);
        return convertToEnrolledStudentDTO(student);
    }

    /**
     * Remove a student from a batch.
     *
     * <p>Deletes the enrollment and discards the student's progress for it. The
     * alternative — leaving orphaned progress rows — makes every later count
     * wrong in a way that is very hard to trace back to here.
     */
    public void removeStudent(Long batchId, Long studentId) {
        Enrollment enrollment = enrollmentRepository.findByBatchIdAndStudentId(batchId, studentId)
                .orElseThrow(() -> new BusinessRuleException("NOT_ENROLLED",
                        "Student is not enrolled in this batch"));

        progressService.discardProgress(studentId, batchId);
        enrollmentRepository.delete(enrollment);

        log.info("Removed student {} from batch {}", studentId, batchId);
        auditLogService.record(AuditAction.STUDENT_UNENROLLED, "Enrollment", studentId,
                AuditAction.OUTCOME_SUCCESS, "{\"batchId\":" + batchId + "}");
    }

    /** A trainer asks for a student to be added to or removed from a batch. */
    public EnrollmentRequestDTO createEnrollmentRequest(Long trainerId, CreateEnrollmentRequestDTO request) {
        Batch batch = requireBatch(request.getBatchId());
        Student student = requireStudent(request.getStudentId());

        Trainer trainer = trainerRepository.findById(trainerId)
                .orElseThrow(() -> ResourceNotFoundException.of("Trainer", trainerId));

        EnrollmentRequest.RequestType requestType;
        try {
            requestType = EnrollmentRequest.RequestType.valueOf(request.getRequestType().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid request type. Must be ADD or REMOVE");
        }

        Optional<EnrollmentRequest> existingRequest = requestRepository.findPendingRequest(
                request.getBatchId(), request.getStudentId(), requestType);

        if (existingRequest.isPresent()) {
            throw new ConflictException("A pending request already exists for this student and batch");
        }

        boolean isEnrolled = enrollmentRepository
                .findByBatchIdAndStudentId(request.getBatchId(), request.getStudentId())
                .isPresent();

        if (requestType == EnrollmentRequest.RequestType.ADD && isEnrolled) {
            throw new ConflictException("Student is already enrolled in this batch");
        }
        if (requestType == EnrollmentRequest.RequestType.REMOVE && !isEnrolled) {
            throw new BusinessRuleException("Student is not enrolled in this batch");
        }

        EnrollmentRequest enrollmentRequest = EnrollmentRequest.builder()
                .batch(batch)
                .student(student)
                .trainer(trainer)
                .collegeId(batch.getCollege().getId())
                .source(RequestSource.TRAINER_REQUEST)
                .requestType(requestType)
                .status(EnrollmentStatus.PENDING)
                .reason(request.getReason())
                .build();

        EnrollmentRequest saved = requestRepository.save(enrollmentRequest);
        log.info("Trainer {} created {} request {} for student {} in batch {}",
                trainerId, requestType, saved.getId(), request.getStudentId(), request.getBatchId());

        return convertToRequestDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<EnrollmentRequestDTO> getPendingRequests() {
        return requestRepository.findAllPending().stream()
                .map(this::convertToRequestDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<EnrollmentRequestDTO> getTrainerRequests(Long trainerId) {
        return requestRepository.findByTrainerIdAndStatus(trainerId, EnrollmentStatus.PENDING).stream()
                .map(this::convertToRequestDTO)
                .collect(Collectors.toList());
    }

    /**
     * Approve a request and carry out what it asked for.
     *
     * <p>The transition is guarded by the state machine, and the entity carries
     * {@code @Version}, so two admins approving the same request concurrently
     * produce one enrollment and one 409 rather than two enrollments and an
     * opaque constraint violation.
     */
    public EnrollmentRequestDTO approveRequest(Long requestId, Long adminUserId) {
        EnrollmentRequest request = requireRequest(requestId);
        User admin = requireUser(adminUserId);

        request.transitionTo(EnrollmentStatus.APPROVED, admin, null);

        if (request.getRequestType() == EnrollmentRequest.RequestType.ADD) {
            applyAdd(request, admin);
        } else {
            applyRemove(request);
        }

        EnrollmentRequest saved = requestRepository.save(request);
        auditLogService.record(AuditAction.ENROLLMENT_APPROVED, "EnrollmentRequest", requestId,
                AuditAction.OUTCOME_SUCCESS,
                "{\"batchId\":" + saved.getBatch().getId()
                        + ",\"studentId\":" + saved.getStudent().getId() + "}");
        log.info("Admin {} approved request {} ({})", adminUserId, requestId, request.getRequestType());

        return convertToRequestDTO(saved);
    }

    private void applyAdd(EnrollmentRequest request, User admin) {
        Long batchId = request.getBatch().getId();
        Long studentId = request.getStudent().getId();

        if (enrollmentRepository.findByBatchIdAndStudentId(batchId, studentId).isPresent()) {
            log.debug("Student {} already enrolled in batch {}; approval is a no-op", studentId, batchId);
            return;
        }

        Enrollment enrollment = Enrollment.builder()
                .batch(request.getBatch())
                .student(request.getStudent())
                .collegeId(request.getCollegeId())
                .status(EnrollmentState.ACTIVE)
                .enrolledBy(admin)
                .build();

        enrollmentRepository.save(enrollment);
        progressService.initialiseProgress(studentId, batchId);

        log.info("Added student {} to batch {}", studentId, batchId);
        auditLogService.record(AuditAction.STUDENT_ENROLLED, "Enrollment", studentId,
                AuditAction.OUTCOME_SUCCESS, "{\"batchId\":" + batchId + "}");
    }

    private void applyRemove(EnrollmentRequest request) {
        Long batchId = request.getBatch().getId();
        Long studentId = request.getStudent().getId();

        enrollmentRepository.findByBatchIdAndStudentId(batchId, studentId)
                .ifPresent(enrollment -> {
                    progressService.discardProgress(studentId, batchId);
                    enrollmentRepository.delete(enrollment);
                    log.info("Removed student {} from batch {}", studentId, batchId);
                });
    }

    /** Decline a request. A reason is worth recording; the applicant will ask. */
    public EnrollmentRequestDTO rejectRequest(Long requestId, Long adminUserId) {
        return rejectRequest(requestId, adminUserId, null);
    }

    public EnrollmentRequestDTO rejectRequest(Long requestId, Long adminUserId, String reason) {
        EnrollmentRequest request = requireRequest(requestId);
        User admin = requireUser(adminUserId);

        request.transitionTo(EnrollmentStatus.REJECTED, admin, reason);

        EnrollmentRequest saved = requestRepository.save(request);
        auditLogService.record(AuditAction.ENROLLMENT_REJECTED, "EnrollmentRequest", requestId,
                AuditAction.OUTCOME_SUCCESS,
                "{\"batchId\":" + saved.getBatch().getId()
                        + ",\"studentId\":" + saved.getStudent().getId() + "}");
        log.info("Admin {} rejected request {}", adminUserId, requestId);

        return convertToRequestDTO(saved);
    }

    /**
     * Expire applications to batches that have already started.
     *
     * <p>Without this, a pending queue accumulates applications to batches that
     * began months ago, and every admin who opens it has to work out which ones
     * are still meaningful.
     */
    public int expireStaleRequests() {
        int expired = requestRepository.expireStaleRequests(LocalDate.now());
        if (expired > 0) {
            log.info("Expired {} enrollment requests for batches that have already started", expired);
        }
        return expired;
    }

    // ------------------------------------------------------------------

    private EnrolledStudentDTO convertToEnrolledStudentDTO(Student student) {
        return EnrolledStudentDTO.builder()
                .studentId(student.getId())
                .fullName(student.getFullName())
                .rollNumber(student.getRollNumber())
                .email(student.getUser().getEmail())
                .department(student.getBranch())
                .year(student.getYear())
                .build();
    }

    /**
     * Map a request to its DTO.
     *
     * <p>Trainer fields are null-guarded: a student application has no trainer,
     * and the previous unguarded {@code request.getTrainer().getId()} would throw
     * on every one of them.
     */
    private EnrollmentRequestDTO convertToRequestDTO(EnrollmentRequest request) {
        Trainer trainer = request.getTrainer();
        User reviewer = request.getReviewedBy();

        return EnrollmentRequestDTO.builder()
                .id(request.getId())
                .batchId(request.getBatch().getId())
                .batchName(request.getBatch().getName())
                .studentId(request.getStudent().getId())
                .studentName(request.getStudent().getFullName())
                .studentRollNumber(request.getStudent().getRollNumber())
                .trainerId(trainer == null ? null : trainer.getId())
                .trainerName(trainer == null ? null : trainer.getFullName())
                .requestType(request.getRequestType().name())
                .status(request.getStatus().name())
                .reason(request.getReason())
                .reviewedBy(reviewer == null ? null : reviewer.getId())
                .reviewedByName(reviewer == null ? null : reviewer.getEmail())
                .reviewedAt(request.getReviewedAt())
                .createdAt(request.getCreatedAt())
                .build();
    }

    // The tenant checks below are why these helpers exist as helpers. Every
    // admin enrollment endpoint takes an id straight from the URL, and
    // findById is not covered by the Hibernate collegeFilter -- a filter
    // applies to queries, not to a load by primary key. Checking here means a
    // new endpoint that reuses these is scoped by construction; a check in each
    // controller would be a check the next endpoint forgets. Ownership failure
    // and a genuine miss both raise the same 404, deliberately.

    private Batch requireBatch(Long batchId) {
        return batchRepository.findById(batchId)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));
    }

    private Student requireStudent(Long studentId) {
        return studentRepository.findById(studentId)
                .filter(st -> TenantGuard.isVisible(st.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));
    }

    private EnrollmentRequest requireRequest(Long requestId) {
        return requestRepository.findById(requestId)
                .filter(r -> TenantGuard.isVisible(r.getCollegeId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Request", requestId));
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));
    }

    /**
     * Refuse to enroll a student into another college's batch.
     *
     * <p>404 rather than 403 — a 403 confirms the batch exists to someone who
     * should not be able to tell.
     */
    private void requireSameCollege(Student student, Batch batch) {
        Long studentCollege = student.getCollege() == null ? null : student.getCollege().getId();
        Long batchCollege = batch.getCollege() == null ? null : batch.getCollege().getId();

        if (studentCollege == null || !studentCollege.equals(batchCollege)) {
            throw ResourceNotFoundException.of("Batch", batch.getId());
        }
    }
}
