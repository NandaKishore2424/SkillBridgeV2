package com.skillbridge.bulkupload.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RoleRepository;
import com.skillbridge.auth.repository.UserRepository;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class BulkUploadJobService {

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

    @Async("bulkUploadExecutor")
    public void processStudentUploadAsync(byte[] data, String fileName, Long bulkUploadId, Long collegeId) {
        log.info("Async student upload started. UploadId: {}", bulkUploadId);
        BulkUpload bulkUpload = bulkUploadRepository.findById(bulkUploadId)
                .orElseThrow(() -> new RuntimeException("Bulk upload record not found"));
        College college = collegeRepository.findById(collegeId)
                .orElseThrow(() -> new RuntimeException("College not found"));

        int successCount = 0;
        int failedCount = 0;

        try {
            List<StudentUploadDTO> students = csvParserService.parseStudentCsv(data, fileName);
            bulkUpload.setTotalRows(students.size());

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

                    BulkUploadResult result = BulkUploadResult.builder()
                            .bulkUpload(bulkUpload)
                            .rowNumber(rowNum)
                            .status("FAILED")
                            .errorMessage(e.getMessage())
                            .data(serialize(dto))
                            .build();
                    bulkUploadResultRepository.save(result);
                }
            }

            bulkUpload.setStatus("COMPLETED");
            bulkUpload.setCompletedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("Failed to parse/process file", e);
            bulkUpload.setStatus("FAILED");
            bulkUpload.setErrorReport(e.getMessage());
            bulkUpload.setCompletedAt(LocalDateTime.now());
        }

        bulkUpload.setSuccessfulRows(successCount);
        bulkUpload.setFailedRows(failedCount);
        bulkUploadRepository.save(bulkUpload);
        log.info("Async student upload finished. UploadId: {}", bulkUploadId);
    }

    @Async("bulkUploadExecutor")
    public void processTrainerUploadAsync(byte[] data, String fileName, Long bulkUploadId, Long collegeId) {
        log.info("Async trainer upload started. UploadId: {}", bulkUploadId);
        BulkUpload bulkUpload = bulkUploadRepository.findById(bulkUploadId)
                .orElseThrow(() -> new RuntimeException("Bulk upload record not found"));
        College college = collegeRepository.findById(collegeId)
                .orElseThrow(() -> new RuntimeException("College not found"));

        int successCount = 0;
        int failedCount = 0;

        try {
            List<TrainerUploadDTO> trainers = csvParserService.parseTrainerCsv(data, fileName);
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

                    BulkUploadResult result = BulkUploadResult.builder()
                            .bulkUpload(bulkUpload)
                            .rowNumber(rowNum)
                            .status("FAILED")
                            .errorMessage(e.getMessage())
                            .data(serialize(dto))
                            .build();
                    bulkUploadResultRepository.save(result);
                }
            }

            bulkUpload.setStatus("COMPLETED");
            bulkUpload.setCompletedAt(LocalDateTime.now());
        } catch (Exception e) {
            log.error("Failed to process trainer file", e);
            bulkUpload.setStatus("FAILED");
            bulkUpload.setErrorReport(e.getMessage());
            bulkUpload.setCompletedAt(LocalDateTime.now());
        }

        bulkUpload.setSuccessfulRows(successCount);
        bulkUpload.setFailedRows(failedCount);
        bulkUploadRepository.save(bulkUpload);
        log.info("Async trainer upload finished. UploadId: {}", bulkUploadId);
    }

    private void processStudentRow(StudentUploadDTO dto, College college, Set<Role> roles, BulkUpload upload,
                                   int rowNum) throws Exception {
        if (userRepository.existsByEmail(dto.getEmail())) {
            throw new IllegalArgumentException("Email already exists: " + dto.getEmail());
        }

        if (studentRepository.existsByRollNumberAndCollegeId(dto.getRollNumber(), college.getId())) {
            throw new IllegalArgumentException("Roll number already exists: " + dto.getRollNumber());
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

        Student student = Student.builder()
                .user(user)
                .college(college)
                .fullName(dto.getFullName())
                .rollNumber(dto.getRollNumber())
                .degree(dto.getDegree())
                .branch(dto.getBranch())
                .year(dto.getYear())
                .build();

        studentRepository.save(student);

        try {
            emailService.sendWelcomeEmail(user, temporaryPassword);
        } catch (Exception e) {
            log.error("Failed to send email to {}", dto.getEmail(), e);
        }

        BulkUploadResult result = BulkUploadResult.builder()
                .bulkUpload(upload)
                .rowNumber(rowNum)
                .status("SUCCESS")
                .entityId(student.getId())
                .data(serialize(dto))
                .build();
        bulkUploadResultRepository.save(result);
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
                .fullName(dto.getFullName())
                .department(dto.getDepartment())
                .specialization(dto.getSpecialization())
                .build();
        trainerRepository.save(trainer);

        try {
            emailService.sendWelcomeEmail(user, temporaryPassword);
        } catch (Exception e) {
            log.error("Failed to send email to {}", dto.getEmail(), e);
        }

        BulkUploadResult result = BulkUploadResult.builder()
                .bulkUpload(upload)
                .rowNumber(rowNum)
                .status("SUCCESS")
                .entityId(trainer.getId())
                .data(serialize(dto))
                .build();
        bulkUploadResultRepository.save(result);
    }

    private String serialize(Object dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception ex) {
            log.error("Failed to serialize DTO", ex);
            return "{}";
        }
    }
}
