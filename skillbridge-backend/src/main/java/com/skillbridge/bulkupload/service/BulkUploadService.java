package com.skillbridge.bulkupload.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.Role.RoleName;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RoleRepository;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.bulkupload.dto.BulkUploadResponse;
import com.skillbridge.bulkupload.dto.StudentUploadDTO;
import com.skillbridge.bulkupload.dto.TrainerUploadDTO;
import com.skillbridge.bulkupload.entity.BulkUpload;
import com.skillbridge.bulkupload.entity.BulkUploadResult;
import com.skillbridge.bulkupload.repository.BulkUploadRepository;
import com.skillbridge.bulkupload.repository.BulkUploadResultRepository;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.shared.service.EmailService;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BulkUploadService {

    private final CsvParserService csvParserService;
    private final BulkUploadRepository bulkUploadRepository;
    private final BulkUploadResultRepository bulkUploadResultRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final CollegeRepository collegeRepository;
    private final RoleRepository roleRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    @Transactional
    public BulkUploadResponse uploadStudents(MultipartFile file, Long collegeId, Long uploadedByUserId) {
        log.info("Starting bulk upload for students. CollegeID: {}, UserID: {}", collegeId, uploadedByUserId);

        // validate file
        csvParserService.validateCsvFormat(file, "STUDENT");

        // Create initial BulkUpload record
        User uploader = userRepository.findById(uploadedByUserId)
                .orElseThrow(() -> new RuntimeException("Uploader not found"));
        College college = collegeRepository.findById(collegeId)
                .orElseThrow(() -> new RuntimeException("College not found"));

        BulkUpload bulkUpload = BulkUpload.builder()
                .college(college)
                .uploadedBy(uploader)
                .entityType("STUDENT")
                .fileName(file.getOriginalFilename())
                .totalRows(0) // Will update
                .status("PROCESSING")
                .build();

        bulkUpload = bulkUploadRepository.save(bulkUpload);

        List<BulkUploadResponse.UploadError> errors = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;

        try {
            List<StudentUploadDTO> students = csvParserService.parseStudentCsv(file);
            bulkUpload.setTotalRows(students.size());

            // Get student role
            Role studentRole = roleRepository.findByName("STUDENT")
                    .orElseThrow(() -> new RuntimeException("Role STUDENT not found"));
            Set<Role> roles = new HashSet<>();
            roles.add(studentRole);

            int rowNum = 0;
            for (StudentUploadDTO dto : students) {
                rowNum++;
                try {
                    processStudentRow(dto, college, roles, bulkUpload, rowNum);
                    successCount++;
                } catch (Exception e) {
                    failedCount++;
                    log.error("Error processing row {}: {}", rowNum, e.getMessage());

                    String jsonData = "{}";
                    try {
                        jsonData = objectMapper.writeValueAsString(dto);
                    } catch (Exception ex) {
                        log.error("Failed to serialize DTO", ex);
                    }

                    // Log failure
                    BulkUploadResult result = BulkUploadResult.builder()
                            .bulkUpload(bulkUpload)
                            .rowNumber(rowNum)
                            .status("FAILED")
                            .errorMessage(e.getMessage())
                            .data(jsonData)
                            .build();
                    bulkUploadResultRepository.save(result);

                    // Add to response
                    Map<String, String> rowData = new HashMap<>();
                    rowData.put("email", dto.getEmail());
                    rowData.put("name", dto.getFullName());
                    errors.add(new BulkUploadResponse.UploadError(rowNum, e.getMessage(), rowData));
                }
            }

            bulkUpload.setStatus("COMPLETED");
            bulkUpload.setCompletedAt(LocalDateTime.now());

        } catch (Exception e) {
            log.error("Failed to parse/process file", e);
            bulkUpload.setStatus("FAILED");
            bulkUpload.setErrorReport(e.getMessage());
            bulkUpload.setCompletedAt(LocalDateTime.now());
            throw e;
        }

        bulkUpload.setSuccessfulRows(successCount);
        bulkUpload.setFailedRows(failedCount);
        bulkUploadRepository.save(bulkUpload);

        return BulkUploadResponse.builder()
                .uploadId(bulkUpload.getId())
                .totalRows(bulkUpload.getTotalRows())
                .successfulRows(successCount)
                .failedRows(failedCount)
                .errors(errors)
                .status(bulkUpload.getStatus())
                .build();
    }

    // Separate method for transaction handling per row could be considered,
    // but for now we want the bulk operation to proceed even if some fail.
    // However, if we want each row to be independent transaction we need
    // self-invocation or move to another bean.
    // For simplicity, we are doing it in one transaction or we can manually handle
    // repository calls.
    // Since we are inside @Transactional method, any unchecked exception will
    // rollback everything.
    // CHECK: We catch exceptions inside the loop, so the transaction won't be
    // marked mostly for rollback unless we rethrow.
    // But saving each user should be persisted.
    // To make sure successful rows are committed even if others fail, we should NOT
    // have @Transactional on the top method
    // or handle transactions manually.
    // Let's remove @Transactional from top and use it on helper if needed, or rely
    // on repository transactional nature.
    // Actually, 'save' is transactional.
    // Better approach: @Transactional on the service method will rollback ALL on
    // exception.
    // But we are catching exceptions in the loop. So partial success IS possible if
    // we don't rethrow.
    // However, we want the BulkUpload record updates to be consistent.

    private void processStudentRow(StudentUploadDTO dto, College college, Set<Role> roles, BulkUpload upload,
            int rowNum) throws Exception {
        // Validation
        if (userRepository.existsByEmail(dto.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + dto.getEmail());
        }

        if (studentRepository.existsByRollNumberAndCollegeId(dto.getRollNumber(), college.getId())) {
            throw new IllegalArgumentException("Roll number already exists: " + dto.getRollNumber());
        }

        // Create User
        // Password is email for now
        String temporaryPassword = dto.getEmail();

        User user = User.builder()
                .email(dto.getEmail())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .collegeId(college.getId())
                .isActive(true)
                .roles(roles)
                .mustChangePassword(true)
                .accountStatus("PENDING_SETUP")
                .invitationSentAt(LocalDateTime.now())
                .build();

        user = userRepository.save(user);

        // Create Student Profile (partial)
        Student student = Student.builder()
                .user(user)
                .college(college)
                .rollNumber(dto.getRollNumber())
                .degree(dto.getDegree())
                .branch(dto.getBranch())
                .year(dto.getYear())
                .build();

        studentRepository.save(student);

        // Send Email
        try {
            emailService.sendWelcomeEmail(user, temporaryPassword);
        } catch (Exception e) {
            log.error("Failed to send email to {}", dto.getEmail(), e);
            // Don't fail the upload just because email failed, but log it?
            // Or maybe we treat it as warning. For now proceeding.
        }

        String jsonData = objectMapper.writeValueAsString(dto);

        // Log Success
        BulkUploadResult result = BulkUploadResult.builder()
                .bulkUpload(upload)
                .rowNumber(rowNum)
                .status("SUCCESS")
                .entityId(student.getId())
                .data(jsonData)
                .build();
        bulkUploadResultRepository.save(result);
    }

    @Transactional
    public BulkUploadResponse uploadTrainers(MultipartFile file, Long collegeId, Long uploadedByUserId) {
        log.info("Starting bulk upload for trainers");
        csvParserService.validateCsvFormat(file, "TRAINER");

        User uploader = userRepository.findById(uploadedByUserId)
                .orElseThrow(() -> new RuntimeException("Uploader not found"));
        College college = collegeRepository.findById(collegeId)
                .orElseThrow(() -> new RuntimeException("College not found"));

        BulkUpload bulkUpload = BulkUpload.builder()
                .college(college)
                .uploadedBy(uploader)
                .entityType("TRAINER")
                .fileName(file.getOriginalFilename())
                .totalRows(0)
                .status("PROCESSING")
                .build();
        bulkUpload = bulkUploadRepository.save(bulkUpload);

        List<BulkUploadResponse.UploadError> errors = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;

        try {
            List<TrainerUploadDTO> trainers = csvParserService.parseTrainerCsv(file);
            bulkUpload.setTotalRows(trainers.size());

            Role trainerRole = roleRepository.findByName("TRAINER")
                    .orElseThrow(() -> new RuntimeException("Role TRAINER not found"));
            Set<Role> roles = new HashSet<>();
            roles.add(trainerRole);

            int rowNum = 0;
            for (TrainerUploadDTO dto : trainers) {
                rowNum++;
                try {
                    processTrainerRow(dto, college, roles, bulkUpload, rowNum);
                    successCount++;
                } catch (Exception e) {
                    failedCount++;
                    log.error("Error processing row {}: {}", rowNum, e.getMessage());

                    String jsonData = "{}";
                    try {
                        jsonData = objectMapper.writeValueAsString(dto);
                    } catch (Exception ex) {
                        log.error("Failed to serialize DTO", ex);
                    }

                    BulkUploadResult result = BulkUploadResult.builder()
                            .bulkUpload(bulkUpload)
                            .rowNumber(rowNum)
                            .status("FAILED")
                            .errorMessage(e.getMessage())
                            .data(jsonData)
                            .build();
                    bulkUploadResultRepository.save(result);

                    Map<String, String> rowData = new HashMap<>();
                    rowData.put("email", dto.getEmail());
                    rowData.put("name", dto.getFullName());
                    errors.add(new BulkUploadResponse.UploadError(rowNum, e.getMessage(), rowData));
                }
            }
            bulkUpload.setStatus("COMPLETED");
            bulkUpload.setCompletedAt(LocalDateTime.now());

        } catch (Exception e) {
            log.error("Failed to process trainer file", e);
            bulkUpload.setStatus("FAILED");
            bulkUpload.setErrorReport(e.getMessage());
            throw e;
        }

        bulkUpload.setSuccessfulRows(successCount);
        bulkUpload.setFailedRows(failedCount);
        bulkUploadRepository.save(bulkUpload);

        return BulkUploadResponse.builder()
                .uploadId(bulkUpload.getId())
                .totalRows(bulkUpload.getTotalRows())
                .successfulRows(successCount)
                .failedRows(failedCount)
                .errors(errors)
                .status(bulkUpload.getStatus())
                .build();
    }

    private void processTrainerRow(TrainerUploadDTO dto, College college, Set<Role> roles, BulkUpload upload,
            int rowNum) throws Exception {
        if (userRepository.existsByEmail(dto.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + dto.getEmail());
        }

        String temporaryPassword = dto.getEmail();

        User user = User.builder()
                .email(dto.getEmail())
                .passwordHash(passwordEncoder.encode(temporaryPassword))
                .collegeId(college.getId())
                .isActive(true)
                .roles(roles)
                .mustChangePassword(true)
                .accountStatus("PENDING_SETUP")
                .invitationSentAt(LocalDateTime.now())
                .build();
        user = userRepository.save(user);

        Trainer trainer = Trainer.builder()
                .user(user)
                .college(college)
                .department(dto.getDepartment())
                .specialization(dto.getSpecialization())
                .build();
        trainerRepository.save(trainer);

        try {
            emailService.sendWelcomeEmail(user, temporaryPassword);
        } catch (Exception e) {
            log.error("Failed to send welcome email", e);
        }

        String jsonData = objectMapper.writeValueAsString(dto);

        BulkUploadResult result = BulkUploadResult.builder()
                .bulkUpload(upload)
                .rowNumber(rowNum)
                .status("SUCCESS")
                .entityId(trainer.getId())
                .data(jsonData)
                .build();
        bulkUploadResultRepository.save(result);
    }

    public List<BulkUpload> getHistory(Long collegeId) {
        return bulkUploadRepository.findByCollegeIdOrderByCreatedAtDesc(collegeId);
    }

    public void resendInvitation(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // This is simplified. In prod, generate a new random password or reset token.
        // For this flow, we are sending current email as password or we should reset
        // password.
        // Let's assume we reset password to email again or send a link.
        // But the requirement said "student can login once and reset their password".
        // The user effectively knows the pattern (email = password).
        // So just re-sending the same email (or slightly different) is enough.

        // However, if the user CHANGED the password, we shouldn't send the old logic.
        // We should check if accountStatus is "PENDING_SETUP".

        if ("PENDING_SETUP".equals(user.getAccountStatus())) {
            try {
                emailService.sendWelcomeEmail(user, user.getEmail());
            } catch (Exception e) {
                log.error("Failed to resend welcome email", e);
                throw new RuntimeException("Failed to send email");
            }
        } else {
            throw new RuntimeException("User is already active or not in pending state");
        }
    }
}
