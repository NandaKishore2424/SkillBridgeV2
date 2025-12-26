package com.skillbridge.bulkupload.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.bulkupload.dto.BulkUploadResponse;
import com.skillbridge.bulkupload.entity.BulkUpload;
import com.skillbridge.bulkupload.service.BulkUploadService;
import com.skillbridge.bulkupload.service.CsvParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class BulkUploadController {

    private final BulkUploadService bulkUploadService;
    private final CsvParserService csvParserService;

    @PostMapping("/students/bulk-upload")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<BulkUploadResponse> uploadStudents(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user) {

        // Assuming user.getCollegeId() is available from the authenticated user object
        // If not populated by Spring Security directly, might need to fetch it.
        // User entity is usually partial in Principal.
        // But for now assuming it's there or we can fetch it via UserService if needed.
        // The User object from AuthenticationPrincipal should depend on your
        // UserDetailsService.
        // If it's your entity, it has collegeId.

        Long collegeId = user.getCollegeId();
        // Fallback or validation if collegeId is null

        return ResponseEntity.ok(bulkUploadService.uploadStudents(file, collegeId, user.getId()));
    }

    @PostMapping("/trainers/bulk-upload")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<BulkUploadResponse> uploadTrainers(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User user) {

        Long collegeId = user.getCollegeId();
        return ResponseEntity.ok(bulkUploadService.uploadTrainers(file, collegeId, user.getId()));
    }

    @GetMapping("/students/bulk-upload/template")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<ByteArrayResource> downloadStudentTemplate() {
        byte[] data = csvParserService.generateStudentTemplate();
        ByteArrayResource resource = new ByteArrayResource(data);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=student_template.csv")
                .contentType(MediaType.TEXT_PLAIN) // or text/csv
                .contentLength(data.length)
                .body(resource);
    }

    @GetMapping("/trainers/bulk-upload/template")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<ByteArrayResource> downloadTrainerTemplate() {
        byte[] data = csvParserService.generateTrainerTemplate();
        ByteArrayResource resource = new ByteArrayResource(data);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment;filename=trainer_template.csv")
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(data.length)
                .body(resource);
    }

    @GetMapping("/students/bulk-upload/history")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<BulkUpload>> getUploadHistory(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(bulkUploadService.getHistory(user.getCollegeId()));
    }

    @GetMapping("/trainers/bulk-upload/history")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<BulkUpload>> getTrainerUploadHistory(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(bulkUploadService.getHistory(user.getCollegeId()));
    }

    @PostMapping("/students/{id}/resend-invitation")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Void> resendStudentInvitation(@PathVariable Long id) {
        bulkUploadService.resendInvitation(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/trainers/{id}/resend-invitation")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Void> resendTrainerInvitation(@PathVariable Long id) {
        bulkUploadService.resendInvitation(id);
        return ResponseEntity.ok().build();
    }
}
