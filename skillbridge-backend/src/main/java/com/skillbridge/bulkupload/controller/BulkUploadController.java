package com.skillbridge.bulkupload.controller;

import com.skillbridge.auth.invitation.InvitationService;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.bulkupload.dto.BulkUploadDetailDTO;
import com.skillbridge.bulkupload.dto.BulkUploadHistoryDTO;
import com.skillbridge.bulkupload.dto.BulkUploadResponse;
import com.skillbridge.bulkupload.dto.BulkUploadRowDTO;
import com.skillbridge.bulkupload.importer.ImportKind;
import com.skillbridge.bulkupload.service.BulkUploadService;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.common.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.CollegeAdminOnly;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * CSV import of students and trainers, and what became of each row.
 *
 * <p>An upload is checked as a whole before this returns: a file that cannot be
 * imported is a 400 naming the problem, and one over the size limit a 413. A
 * file that can be is 202 and imports in the background; the same file sent
 * again is 200 with the first upload's id. Row outcomes are at
 * {@code /bulk-uploads/{id}/rows}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class BulkUploadController {

    private static final Set<String> ROW_STATUSES = Set.of("SUCCESS", "EMAIL_FAILED", "FAILED");

    private final BulkUploadService bulkUploadService;
    private final InvitationService invitationService;

    @PostMapping("/students/bulk-upload")
    @CollegeAdminOnly
    public ResponseEntity<BulkUploadResponse> uploadStudents(@RequestParam("file") MultipartFile file) {
        return upload(ImportKind.STUDENT, file);
    }

    @PostMapping("/trainers/bulk-upload")
    @CollegeAdminOnly
    public ResponseEntity<BulkUploadResponse> uploadTrainers(@RequestParam("file") MultipartFile file) {
        return upload(ImportKind.TRAINER, file);
    }

    @GetMapping("/students/bulk-upload/template")
    @CollegeAdminOnly
    public ResponseEntity<byte[]> downloadStudentTemplate() {
        return template(ImportKind.STUDENT, "student_template.csv");
    }

    @GetMapping("/trainers/bulk-upload/template")
    @CollegeAdminOnly
    public ResponseEntity<byte[]> downloadTrainerTemplate() {
        return template(ImportKind.TRAINER, "trainer_template.csv");
    }

    /** Upload history, newest first. Paged because {@code bulk_uploads} only grows. */
    @GetMapping("/students/bulk-upload/history")
    @CollegeAdminOnly
    public ResponseEntity<PagedResponse<BulkUploadHistoryDTO>> getUploadHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(
                bulkUploadService.getHistory(SecurityUtils.requireCollegeId(), "STUDENT", Pagination.of(page, size)),
                BulkUploadHistoryDTO::from));
    }

    @GetMapping("/trainers/bulk-upload/history")
    @CollegeAdminOnly
    public ResponseEntity<PagedResponse<BulkUploadHistoryDTO>> getTrainerUploadHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(
                bulkUploadService.getHistory(SecurityUtils.requireCollegeId(), "TRAINER", Pagination.of(page, size)),
                BulkUploadHistoryDTO::from));
    }

    @GetMapping("/bulk-uploads/{id}")
    @CollegeAdminOnly
    public ResponseEntity<BulkUploadDetailDTO> getUpload(@PathVariable Long id) {
        return ResponseEntity.ok(bulkUploadService.getUpload(id, SecurityUtils.requireCollegeId()));
    }

    /**
     * An upload's rows, in file order.
     *
     * @param status comma-separated; defaults to the rows that need attention
     */
    @GetMapping("/bulk-uploads/{id}/rows")
    @CollegeAdminOnly
    public ResponseEntity<PagedResponse<BulkUploadRowDTO>> getUploadRows(
            @PathVariable Long id,
            @RequestParam(defaultValue = "FAILED,EMAIL_FAILED") Set<String> status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Set<String> statuses = status.stream().map(s -> s.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        if (!ROW_STATUSES.containsAll(statuses)) {
            throw new BadRequestException("status must be any of " + String.join(", ", ROW_STATUSES));
        }
        return ResponseEntity.ok(PagedResponse.from(
                bulkUploadService.getRows(id, SecurityUtils.requireCollegeId(), statuses, Pagination.of(page, size)),
                Function.identity()));
    }

    @PostMapping("/students/{id}/resend-invitation")
    @CollegeAdminOnly
    public ResponseEntity<Void> resendStudentInvitation(@PathVariable Long id) {
        invitationService.resend(id, "STUDENT");
        return ResponseEntity.ok().build();
    }

    @PostMapping("/trainers/{id}/resend-invitation")
    @CollegeAdminOnly
    public ResponseEntity<Void> resendTrainerInvitation(@PathVariable Long id) {
        invitationService.resend(id, "TRAINER");
        return ResponseEntity.ok().build();
    }

    private ResponseEntity<BulkUploadResponse> upload(ImportKind kind, MultipartFile file) {
        Long collegeId = SecurityUtils.requireCollegeId();
        Long uploaderId = SecurityUtils.currentUser().getId();
        if (file.isEmpty()) {
            throw new BadRequestException("The file is empty.");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new BadRequestException("Upload a .csv file. In Excel: File > Save As > CSV UTF-8.");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("The upload could not be read; try again.");
        }
        BulkUploadResponse response = bulkUploadService.start(kind, data, name, collegeId, uploaderId);
        return ResponseEntity.status(response.alreadyUploaded() ? HttpStatus.OK : HttpStatus.ACCEPTED).body(response);
    }

    private static ResponseEntity<byte[]> template(ImportKind kind, String fileName) {
        byte[] data = kind.template().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(data);
    }
}
