package com.skillbridge.bulkupload.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.bulkupload.dto.BulkUploadDetailDTO;
import com.skillbridge.bulkupload.dto.BulkUploadResponse;
import com.skillbridge.bulkupload.dto.BulkUploadRowDTO;
import com.skillbridge.bulkupload.entity.BulkUpload;
import com.skillbridge.bulkupload.entity.BulkUploadResult;
import com.skillbridge.bulkupload.importer.BulkUploadJob;
import com.skillbridge.bulkupload.importer.CsvImportFile;
import com.skillbridge.bulkupload.importer.ImportKind;
import com.skillbridge.bulkupload.repository.BulkUploadRepository;
import com.skillbridge.bulkupload.repository.BulkUploadResultRepository;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class BulkUploadService {

    private final BulkUploadRepository uploads;
    private final BulkUploadResultRepository results;
    private final UserRepository userRepository;
    private final CollegeRepository collegeRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final BulkUploadJob job;
    private final ObjectMapper objectMapper;
    private final int maxRows;

    public BulkUploadService(BulkUploadRepository uploads, BulkUploadResultRepository results,
                             UserRepository userRepository, CollegeRepository collegeRepository,
                             StudentRepository studentRepository, TrainerRepository trainerRepository,
                             BulkUploadJob job, ObjectMapper objectMapper,
                             @Value("${app.import.max-rows:2000}") int maxRows) {
        this.uploads = uploads;
        this.results = results;
        this.userRepository = userRepository;
        this.collegeRepository = collegeRepository;
        this.studentRepository = studentRepository;
        this.trainerRepository = trainerRepository;
        this.job = job;
        this.objectMapper = objectMapper;
        this.maxRows = maxRows;
    }

    /**
     * Checks the file, records the upload and queues the import.
     *
     * <p>Not transactional: the upload row must be committed before the import
     * starts on another thread, or that thread cannot see it. {@code save}
     * commits on its own.
     *
     * <p>The same bytes uploaded again for the same college and kind are not
     * imported twice; the first upload is returned. The lookup is the fast path
     * and {@code uk_bulk_uploads_same_file} (V5) the guarantee: of two
     * simultaneous uploads, one insert fails and returns the other's upload.
     */
    public BulkUploadResponse start(ImportKind kind, byte[] data, String fileName, Long collegeId, Long uploaderId) {
        CsvImportFile file = CsvImportFile.parse(data, kind, maxRows);
        String sha256 = sha256(data);

        BulkUpload existing = uploads.findLiveBySameFile(collegeId, kind.name(), sha256).orElse(null);
        if (existing != null) {
            return new BulkUploadResponse(existing.getId(), existing.getStatus(), file.rowCount(), true);
        }

        BulkUpload upload;
        try {
            upload = uploads.saveAndFlush(BulkUpload.builder()
                    .college(collegeRepository.getReferenceById(collegeId))
                    .uploadedBy(userRepository.getReferenceById(uploaderId))
                    .entityType(kind.name())
                    .fileName(fileName == null || fileName.isBlank() ? "upload.csv" : fileName)
                    .totalRows(0)
                    .status("PROCESSING")
                    .fileSha256(sha256)
                    .lastProgressAt(java.time.LocalDateTime.now())
                    .build());
        } catch (DataIntegrityViolationException raced) {
            BulkUpload winner = uploads.findLiveBySameFile(collegeId, kind.name(), sha256).orElseThrow(() -> raced);
            return new BulkUploadResponse(winner.getId(), winner.getStatus(), file.rowCount(), true);
        }

        job.run(upload.getId(), collegeId, file);
        return new BulkUploadResponse(upload.getId(), "PROCESSING", file.rowCount(), false);
    }

    /**
     * Read-only, and transactional on purpose. Without an ambient transaction
     * each repository call opens and commits its own, and
     * {@link com.skillbridge.common.tenant.TenantFilterAspect} has no session
     * to enable {@code collegeFilter} on, since it only advises
     * {@code @Transactional} methods.
     *
     * <p>Paged: {@code bulk_uploads} is append-only, one row per upload forever.
     */
    @Transactional(readOnly = true)
    public Page<BulkUpload> getHistory(Long collegeId, String entityType, Pageable pageable) {
        return uploads.findByCollegeIdAndEntityTypeOrderByCreatedAtDesc(collegeId, entityType, pageable);
    }

    /** One upload of the caller's college; another college's is a 404, like a missing one. */
    @Transactional(readOnly = true)
    public BulkUploadDetailDTO getUpload(Long uploadId, Long collegeId) {
        BulkUpload upload = requireOwn(uploadId, collegeId);
        return BulkUploadDetailDTO.from(upload, results.countByBulkUploadIdAndStatus(uploadId, "EMAIL_FAILED"));
    }

    /**
     * An upload's rows with the given statuses, in file order.
     *
     * @param statuses any of SUCCESS, EMAIL_FAILED, FAILED
     */
    @Transactional(readOnly = true)
    public Page<BulkUploadRowDTO> getRows(Long uploadId, Long collegeId, Set<String> statuses, Pageable pageable) {
        BulkUpload upload = requireOwn(uploadId, collegeId);
        Page<BulkUploadResult> page = results.findByBulkUploadIdAndStatusInOrderByRowNumber(uploadId, statuses, pageable);
        Map<Long, Long> userIds = userIdsByEntityId(upload.getEntityType(),
                page.getContent().stream().map(BulkUploadResult::getEntityId).filter(Objects::nonNull).toList());
        return page.map(r -> new BulkUploadRowDTO(r.getRowNumber(), r.getStatus(), r.getErrorMessage(),
                values(r.getData()), r.getEntityId() == null ? null : userIds.get(r.getEntityId())));
    }

    private BulkUpload requireOwn(Long uploadId, Long collegeId) {
        return uploads.findByIdAndCollegeId(uploadId, collegeId)
                .orElseThrow(() -> ResourceNotFoundException.of("Upload", uploadId));
    }

    /** One query for the page, not one per row. */
    private Map<Long, Long> userIdsByEntityId(String entityType, List<Long> entityIds) {
        if (entityIds.isEmpty()) {
            return Map.of();
        }
        return "TRAINER".equals(entityType)
                ? trainerRepository.findAllById(entityIds).stream()
                        .collect(Collectors.toMap(Trainer::getId, t -> t.getUser().getId()))
                : studentRepository.findAllById(entityIds).stream()
                        .collect(Collectors.toMap(Student::getId, s -> s.getUser().getId()));
    }

    private Map<String, String> values(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            // Rows written before 2026-09-19 hold the old DTO's JSON, keyed by field
            // rather than column; if one will not read, show no values rather than
            // fail the page.
            return Map.of();
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }
}
