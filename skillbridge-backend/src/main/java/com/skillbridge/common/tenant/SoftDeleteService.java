package com.skillbridge.common.tenant;

import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.company.entity.Company;
import com.skillbridge.company.repository.CompanyRepository;
import com.skillbridge.enrollment.domain.EnrollmentState;
import com.skillbridge.enrollment.repository.EnrollmentRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Soft deletion of the four top-level entities a college admin owns.
 *
 * <p>Until now nothing in this application could be deleted at all — there was
 * no DELETE mapping for any top-level entity, and the UI's delete buttons had
 * nothing to call. This adds the capability without adding the danger: rows are
 * marked, never removed, so an accidental delete is a one-column update to undo
 * and the audit trail, enrollments and progress history all survive.
 *
 * <p>Each method refuses rather than cascading when live data hangs off the
 * row. That is a deliberate choice against the more convenient alternative of
 * quietly deleting the children too: a college admin who deletes a batch with
 * thirty enrolled students has almost certainly clicked the wrong row, and the
 * right response is to say so. The message names the number, so the refusal is
 * actionable rather than merely obstructive.
 *
 * <p>Every lookup is tenant-guarded and every failure — missing, or belonging to
 * another college — is the same 404.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SoftDeleteService {

    private final BatchRepository batchRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final CompanyRepository companyRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final UserRepository userRepository;
    private final com.skillbridge.auth.service.TokenRevocationService tokenRevocation;

    @Transactional
    public void deleteBatch(Long batchId, Long actorId) {
        Batch batch = batchRepository.findById(batchId)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", batchId));

        long active = enrollmentRepository.countByBatchIdAndStatus(batchId, EnrollmentState.ACTIVE);
        if (active > 0 && !"COMPLETED".equals(batch.getStatus())) {
            throw new ConflictException("BATCH_HAS_ENROLLMENTS",
                    "This batch has " + active + " actively enrolled student"
                            + (active == 1 ? "" : "s")
                            + ". Mark it COMPLETED, or remove the students, before deleting it.");
        }

        batch.setDeletedAt(LocalDateTime.now());
        batch.setDeletedBy(actorId);
        batchRepository.save(batch);
        log.info("Batch {} soft-deleted by user {}", batchId, actorId);
    }

    @Transactional
    public void deleteStudent(Long studentId, Long actorId) {
        Student student = studentRepository.findById(studentId)
                .filter(s -> TenantGuard.isVisible(s.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Student", studentId));

        long active = enrollmentRepository.countByStudentIdAndStatus(studentId, EnrollmentState.ACTIVE);
        if (active > 0) {
            throw new ConflictException("STUDENT_HAS_ENROLLMENTS",
                    "This student is enrolled in " + active + " active batch"
                            + (active == 1 ? "" : "es")
                            + ". Remove them from those batches before deleting.");
        }

        student.setDeletedAt(LocalDateTime.now());
        student.setDeletedBy(actorId);
        studentRepository.save(student);

        // The account must go too, or the person can still sign in to an
        // account whose profile no longer resolves — every /students/me read
        // would 404 for them.
        deactivateAccount(student.getUser().getId());
        log.info("Student {} soft-deleted by user {}", studentId, actorId);
    }

    @Transactional
    public void deleteTrainer(Long trainerId, Long actorId) {
        Trainer trainer = trainerRepository.findById(trainerId)
                .filter(t -> TenantGuard.isVisible(t.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Trainer", trainerId));

        long assigned = batchRepository.countLiveBatchesForTrainer(trainerId);
        if (assigned > 0) {
            throw new ConflictException("TRAINER_HAS_BATCHES",
                    "This trainer is assigned to " + assigned + " batch"
                            + (assigned == 1 ? "" : "es")
                            + ". Unassign them before deleting.");
        }

        trainer.setDeletedAt(LocalDateTime.now());
        trainer.setDeletedBy(actorId);
        trainerRepository.save(trainer);
        deactivateAccount(trainer.getUser().getId());
        log.info("Trainer {} soft-deleted by user {}", trainerId, actorId);
    }

    @Transactional
    public void deleteCompany(Long companyId, Long actorId) {
        Company company = companyRepository.findById(companyId)
                .filter(c -> c.getCollege() == null || TenantGuard.isVisible(c.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Company", companyId));

        long linked = batchRepository.countLiveBatchesForCompany(companyId);
        if (linked > 0) {
            throw new ConflictException("COMPANY_LINKED_TO_BATCHES",
                    "This company is linked to " + linked + " batch"
                            + (linked == 1 ? "" : "es")
                            + ". Unlink it before deleting.");
        }

        company.setDeletedAt(LocalDateTime.now());
        company.setDeletedBy(actorId);
        companyRepository.save(company);
        log.info("Company {} soft-deleted by user {}", companyId, actorId);
    }

    private void deactivateAccount(Long userId) {
        userRepository.findById(userId).ifPresent(u -> {
            u.setIsActive(false);
            u.setUpdatedAt(LocalDateTime.now());
            userRepository.save(u);
            // Same reason as the status endpoints: the request path reads the
            // token's claims, so a soft-deleted account would otherwise keep
            // working until its access token expired.
            tokenRevocation.revoke(u.getId());
        });
    }
}
