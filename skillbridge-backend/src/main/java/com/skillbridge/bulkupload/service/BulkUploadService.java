package com.skillbridge.bulkupload.service;

import com.skillbridge.common.tenant.TenantGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.TemporaryPasswordGenerator;
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
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.InternalServerException;
import com.skillbridge.common.exception.ResourceNotFoundException;

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
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final BulkUploadJobService bulkUploadJobService;

        public BulkUploadResponse startStudentUpload(byte[] data, String fileName, Long collegeId, Long uploadedByUserId) {
        User uploader = userRepository.findById(uploadedByUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Uploader not found"));
        College college = collegeRepository.findById(collegeId)
            .orElseThrow(() -> new ResourceNotFoundException("College not found"));

        BulkUpload bulkUpload = BulkUpload.builder()
            .college(college)
            .uploadedBy(uploader)
            .entityType("STUDENT")
            .fileName(fileName)
            .totalRows(0)
            .status("PROCESSING")
            .build();
        bulkUpload = bulkUploadRepository.save(bulkUpload);

        bulkUploadJobService.processStudentUploadAsync(data, fileName, bulkUpload.getId(), collegeId);

        return BulkUploadResponse.builder()
            .uploadId(bulkUpload.getId())
            .totalRows(0)
            .successfulRows(0)
            .failedRows(0)
            .errors(new ArrayList<>())
            .status("PROCESSING")
            .build();
        }

        public BulkUploadResponse startTrainerUpload(byte[] data, String fileName, Long collegeId, Long uploadedByUserId) {
        User uploader = userRepository.findById(uploadedByUserId)
            .orElseThrow(() -> new ResourceNotFoundException("Uploader not found"));
        College college = collegeRepository.findById(collegeId)
            .orElseThrow(() -> new ResourceNotFoundException("College not found"));

        BulkUpload bulkUpload = BulkUpload.builder()
            .college(college)
            .uploadedBy(uploader)
            .entityType("TRAINER")
            .fileName(fileName)
            .totalRows(0)
            .status("PROCESSING")
            .build();
        bulkUpload = bulkUploadRepository.save(bulkUpload);

        bulkUploadJobService.processTrainerUploadAsync(data, fileName, bulkUpload.getId(), collegeId);

        return BulkUploadResponse.builder()
            .uploadId(bulkUpload.getId())
            .totalRows(0)
            .successfulRows(0)
            .failedRows(0)
            .errors(new ArrayList<>())
            .status("PROCESSING")
            .build();
        }

    /**
     * History for one kind of upload.
     *
     * <p>Both history endpoints once called an unfiltered variant and so
     * returned identical lists — asking for student upload history handed back
     * trainer uploads. The filtering finder had existed on the repository the
     * whole time and was simply never called. The unfiltered variant is gone
     * so it cannot be reached for again.
     *
     * <p>Paged: {@code bulk_uploads} is append-only, one row per upload
     * forever, so the history is the fastest-growing list an admin can ask
     * for after the audit log.
     *
     * @param entityType {@code STUDENT} or {@code TRAINER}, matching
     *                   {@code BulkUpload.entityType}
     */
    /**
     * Read-only, and transactional on purpose. Without an ambient transaction
     * each repository call opens and commits its own, which costs a round trip
     * per call to a database in another region -- measured at 5.00 connection
     * checkouts for one GET /admin/students -- and leaves
     * {@link com.skillbridge.common.tenant.TenantFilterAspect} with no session
     * to enable {@code collegeFilter} on, since it only advises
     * {@code @Transactional} methods. One transaction also means one snapshot,
     * so a page and the rows it is mapped from cannot disagree.
     */
    @Transactional(readOnly = true)
    public Page<BulkUpload> getHistory(Long collegeId, String entityType, Pageable pageable) {
        return bulkUploadRepository.findByCollegeIdAndEntityTypeOrderByCreatedAtDesc(
                collegeId, entityType, pageable);
    }
}
