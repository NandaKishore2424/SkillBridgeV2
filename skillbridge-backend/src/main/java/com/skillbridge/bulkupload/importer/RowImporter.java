package com.skillbridge.bulkupload.importer;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.invitation.IssuedInvitation;
import com.skillbridge.auth.repository.RoleRepository;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.security.TemporaryPasswordGenerator;
import com.skillbridge.bulkupload.dto.StudentUploadDTO;
import com.skillbridge.bulkupload.dto.TrainerUploadDTO;
import com.skillbridge.bulkupload.entity.BulkUploadResult;
import com.skillbridge.bulkupload.repository.BulkUploadRepository;
import com.skillbridge.bulkupload.repository.BulkUploadResultRepository;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Every write the importer makes, each in a transaction of its own.
 *
 * <p>One row, one transaction: the user, the student or trainer, and the row's
 * SUCCESS result commit together or not at all. The old importer had no
 * transaction, so a row that failed after its user was saved left an orphan
 * user behind (measured 2026-09-17), and the file could not be uploaded again
 * because that email now "already existed".
 *
 * <p>REQUIRES_NEW rather than REQUIRED because the caller may itself be
 * transactional in a test or a future caller; a row must commit on its own
 * regardless.
 */
@Service
public class RowImporter {

    static final String ALREADY_EXISTS = "An account with this email already exists";

    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final RoleRepository roleRepository;
    private final CollegeRepository collegeRepository;
    private final BulkUploadRepository uploadRepository;
    private final BulkUploadResultRepository resultRepository;
    private final PasswordEncoder passwordEncoder;

    public RowImporter(UserRepository userRepository, StudentRepository studentRepository,
                       TrainerRepository trainerRepository, RoleRepository roleRepository,
                       CollegeRepository collegeRepository, BulkUploadRepository uploadRepository,
                       BulkUploadResultRepository resultRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.studentRepository = studentRepository;
        this.trainerRepository = trainerRepository;
        this.roleRepository = roleRepository;
        this.collegeRepository = collegeRepository;
        this.uploadRepository = uploadRepository;
        this.resultRepository = resultRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportedAccount importStudent(Long uploadId, int rowNumber, StudentUploadDTO dto, Long collegeId,
                                         String rowJson) {
        if (userRepository.existsByEmail(dto.getEmail())) {
            throw new RowRejectedException(ALREADY_EXISTS);
        }
        if (studentRepository.existsByRollNumberAndCollegeId(dto.getRollNumber(), collegeId)) {
            throw new RowRejectedException("Roll Number " + dto.getRollNumber() + " is already used in this college");
        }
        College college = collegeRepository.getReferenceById(collegeId);
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        User user = userRepository.save(invitedUser(dto.getEmail(), collegeId, "STUDENT", temporaryPassword));
        Student student = studentRepository.save(Student.builder()
                .user(user)
                .college(college)
                .fullName(dto.getFullName())
                .rollNumber(dto.getRollNumber())
                .degree(dto.getDegree())
                .branch(dto.getBranch())
                .year(dto.getYear())
                .build());
        Long resultId = saveResult(uploadId, rowNumber, "SUCCESS", student.getId(), null, rowJson);
        return new ImportedAccount(resultId, new IssuedInvitation(user.getEmail(), temporaryPassword));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ImportedAccount importTrainer(Long uploadId, int rowNumber, TrainerUploadDTO dto, Long collegeId,
                                         String rowJson) {
        if (userRepository.existsByEmail(dto.getEmail())) {
            throw new RowRejectedException(ALREADY_EXISTS);
        }
        College college = collegeRepository.getReferenceById(collegeId);
        String temporaryPassword = TemporaryPasswordGenerator.generate();
        User user = userRepository.save(invitedUser(dto.getEmail(), collegeId, "TRAINER", temporaryPassword));
        Trainer trainer = trainerRepository.save(Trainer.builder()
                .user(user)
                .college(college)
                .fullName(dto.getFullName())
                .department(dto.getDepartment())
                .specialization(dto.getSpecialization())
                .build());
        Long resultId = saveResult(uploadId, rowNumber, "SUCCESS", trainer.getId(), null, rowJson);
        return new ImportedAccount(resultId, new IssuedInvitation(user.getEmail(), temporaryPassword));
    }

    /** A row that wrote nothing. Its own transaction, after the row's rolled back. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long uploadId, int rowNumber, String rowJson, String reason) {
        saveResult(uploadId, rowNumber, "FAILED", null, reason, rowJson);
    }

    /** The account exists, but its invitation did not go out; the admin resends it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEmailFailure(Long resultId, String reason) {
        resultRepository.findById(resultId).ifPresent(r -> {
            r.setStatus("EMAIL_FAILED");
            r.setErrorMessage(reason);
        });
    }

    /** Counters so far, and the heartbeat StaleUploadSweeper watches. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(Long uploadId, int total, int succeeded, int failed) {
        uploadRepository.updateProgress(uploadId, total, succeeded, failed, LocalDateTime.now());
    }

    /**
     * Terminal state. Only from PROCESSING: if the sweeper already declared this
     * upload dead, a late finish does not resurrect it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long uploadId, int total, int succeeded, int failed, String status, String errorReport) {
        LocalDateTime now = LocalDateTime.now();
        uploadRepository.finish(uploadId, total, succeeded, failed, status, errorReport, now);
    }

    private User invitedUser(String email, Long collegeId, String roleName, String temporaryPassword) {
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new IllegalStateException("Role " + roleName + " is missing (Flyway V2 seeds it)"));
        Set<Role> roles = new HashSet<>();
        roles.add(role);
        return User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .collegeId(collegeId)
                .isActive(true)
                .roles(roles)
                .mustChangePassword(true)
                .accountStatus("PENDING_SETUP")
                .invitationSentAt(LocalDateTime.now())
                .build();
    }

    private Long saveResult(Long uploadId, int rowNumber, String status, Long entityId, String message, String rowJson) {
        return resultRepository.save(BulkUploadResult.builder()
                .bulkUpload(uploadRepository.getReferenceById(uploadId))
                .rowNumber(rowNumber)
                .status(status)
                .entityId(entityId)
                .errorMessage(message)
                .data(rowJson)
                .build()).getId();
    }
}
