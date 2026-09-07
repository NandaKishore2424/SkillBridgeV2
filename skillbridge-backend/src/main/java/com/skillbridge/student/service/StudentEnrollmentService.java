package com.skillbridge.student.service;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.enrollment.domain.EnrollmentStatus;
import com.skillbridge.enrollment.domain.RequestSource;
import com.skillbridge.enrollment.entity.EnrollmentRequest;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.enrollment.repository.EnrollmentRequestRepository;
import com.skillbridge.student.dto.BatchApplicationDTO;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Student-initiated enrollment: applying to a batch and withdrawing.
 *
 * <p>Kept separate from {@code EnrollmentManagementService}, which is the
 * admin/trainer side. The two have different actors, different authorisation and
 * different failure modes, and folding them together produces a service where
 * every method needs an "is this an admin?" branch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class StudentEnrollmentService {

    private final StudentRepository studentRepository;
    private final BatchRepository batchRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final EnrollmentRequestRepository requestRepository;
    private final UserRepository userRepository;

    /**
     * Apply to a batch.
     *
     * <p>Four things have to hold, and each is checked in the order that fails
     * cheapest first: the batch exists and is open, the student is in the same
     * college, they are not already enrolled, and there is a seat left.
     *
     * <p><b>On the capacity check.</b> It loads the batch with a pessimistic write
     * lock and counts active enrollments under that lock. Optimistic locking
     * cannot solve this one: concurrent applicants insert <em>different</em>
     * enrollment rows, so no version ever collides — each transaction is
     * perfectly consistent on its own and the invariant still breaks. Serialising
     * on the batch row is what makes a capacity of 30 mean 30.
     *
     * <p><b>On idempotency.</b> A retry returns the existing application rather
     * than a 409. The partial unique index on pending requests is the backstop:
     * if two requests race past the pre-check, one insert loses and is caught
     * here, then re-read. The check and the constraint say the same thing, so a
     * race degrades into a duplicate response, never a duplicate row.
     */
    @Transactional
    public BatchApplicationDTO applyToBatch(Long userId, Long batchId) {
        Student student = requireStudentByUser(userId);

        // Lock the batch first. Taking the lock before the reads means every
        // applicant for this batch queues here in a consistent order, which is
        // also what keeps this free of deadlocks: there is only ever one lock.
        Batch batch = batchRepository.findByIdForUpdate(batchId)
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));

        requireSameCollege(student, batch);

        if (!batch.isOpenForEnrollment()) {
            throw new BusinessRuleException("BATCH_NOT_OPEN",
                    "This batch is not accepting applications. Its status is " + batch.getStatus() + ".");
        }

        if (enrollmentRepository.findByBatchIdAndStudentId(batchId, student.getId()).isPresent()) {
            throw new ConflictException("ALREADY_ENROLLED",
                    "You are already enrolled in this batch.");
        }

        // A retry, or a double-click, lands here and gets the original back.
        var existing = requestRepository.findPendingRequest(
                batchId, student.getId(), EnrollmentRequest.RequestType.ADD);
        if (existing.isPresent()) {
            return toDto(existing.get(), true,
                    "You have already applied to this batch. Your application is awaiting review.");
        }

        assertCapacityAvailable(batch);

        EnrollmentRequest application = EnrollmentRequest.builder()
                .batch(batch)
                .student(student)
                .trainer(null)                       // a student application has no trainer
                .collegeId(batch.getCollege().getId())
                .source(RequestSource.STUDENT_APPLICATION)
                .requestType(EnrollmentRequest.RequestType.ADD)
                .status(EnrollmentStatus.PENDING)
                .reason("Student application")
                .build();

        try {
            EnrollmentRequest saved = requestRepository.saveAndFlush(application);
            log.info("Student {} applied to batch {} (application {})",
                    student.getId(), batchId, saved.getId());
            return toDto(saved, false, "Application submitted. You will be notified once it is reviewed.");

        } catch (DataIntegrityViolationException ex) {
            // The partial unique index rejected a concurrent duplicate. Re-read
            // and return the winner rather than surfacing a constraint error.
            log.debug("Duplicate application race for student {} on batch {}", student.getId(), batchId);
            return requestRepository
                    .findPendingRequest(batchId, student.getId(), EnrollmentRequest.RequestType.ADD)
                    .map(r -> toDto(r, true, "You have already applied to this batch."))
                    .orElseThrow(() -> ex);
        }
    }

    /**
     * Withdraw an application that has not been reviewed yet.
     *
     * <p>The legality of the transition is decided by
     * {@code EnrollmentStatus.PENDING.allowedNext()}, not by an if-statement here,
     * so an attempt to withdraw an already-approved application returns a 409
     * that names both states.
     */
    @Transactional
    public BatchApplicationDTO withdrawApplication(Long userId, Long applicationId) {
        Student student = requireStudentByUser(userId);
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        EnrollmentRequest application = requestRepository.findById(applicationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Application", applicationId));

        // 404 rather than 403: confirming the application exists would tell the
        // caller they guessed a real id belonging to someone else.
        if (!application.getStudent().getId().equals(student.getId())) {
            throw ResourceNotFoundException.of("Application", applicationId);
        }

        application.transitionTo(EnrollmentStatus.WITHDRAWN, user, "Withdrawn by the student");
        requestRepository.save(application);

        log.info("Student {} withdrew application {}", student.getId(), applicationId);
        return toDto(application, false, "Application withdrawn.");
    }

    /** Every application this student has made, newest first. */
    public Page<BatchApplicationDTO> getMyApplications(Long userId, Pageable pageable) {
        Student student = requireStudentByUser(userId);
        return requestRepository.findByStudent(student.getId(), pageable)
                .map(r -> toDto(r, false, null));
    }

    // ------------------------------------------------------------------

    /**
     * Refuse the application if the batch is full.
     *
     * <p>Counts approved-and-active enrollments plus applications already pending.
     * Counting only enrollments would let a batch with 30 seats accumulate 200
     * pending applications, all of which an admin then has to reject by hand.
     */
    private void assertCapacityAvailable(Batch batch) {
        Integer capacity = batch.getCapacity();
        if (capacity == null) {
            return;
        }

        long enrolled = enrollmentRepository.countActiveForUpdate(batch.getId());
        long pending = requestRepository.countByBatchIdAndStatus(batch.getId(), EnrollmentStatus.PENDING);

        if (enrolled + pending >= capacity) {
            throw new BusinessRuleException("BATCH_FULL",
                    "This batch is full. " + enrolled + " of " + capacity
                            + " places are taken and " + pending + " applications are already under review.");
        }
    }

    private Student requireStudentByUser(Long userId) {
        return studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No student profile found. Complete your profile setup first."));
    }

    private void requireSameCollege(Student student, Batch batch) {
        Long studentCollege = student.getCollege() == null ? null : student.getCollege().getId();
        Long batchCollege = batch.getCollege() == null ? null : batch.getCollege().getId();

        // 404, not 403 — see the note in ResourceNotFoundException.
        if (studentCollege == null || !studentCollege.equals(batchCollege)) {
            throw ResourceNotFoundException.of("Batch", batch.getId());
        }
    }

    private BatchApplicationDTO toDto(EnrollmentRequest r, boolean duplicate, String message) {
        return BatchApplicationDTO.builder()
                .applicationId(r.getId())
                .batchId(r.getBatch().getId())
                .batchName(r.getBatch().getName())
                .status(r.getStatus())
                .appliedAt(r.getCreatedAt())
                .duplicate(duplicate)
                .message(message)
                .build();
    }
}
