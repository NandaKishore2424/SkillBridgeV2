package com.skillbridge.bulkupload.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.bulkupload.dto.BulkUploadHistoryDTO;
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
import com.skillbridge.common.exception.BadRequestException;

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
            @RequestParam("file") MultipartFile file) {

        AuthenticatedUser user = SecurityUtils.currentUser();
        Long collegeId = SecurityUtils.requireCollegeId();
        csvParserService.validateCsvFormat(file, "STUDENT");

        try {
            byte[] data = file.getBytes();
            BulkUploadResponse response = bulkUploadService.startStudentUpload(
                    data,
                    file.getOriginalFilename(),
                    collegeId,
                    user.getId()
            );
            return ResponseEntity.accepted().body(response);
        } catch (Exception e) {
            throw new BadRequestException("Failed to read upload file: " + e.getMessage());
        }
    }

    @PostMapping("/trainers/bulk-upload")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<BulkUploadResponse> uploadTrainers(
            @RequestParam("file") MultipartFile file) {

        AuthenticatedUser user = SecurityUtils.currentUser();
        Long collegeId = SecurityUtils.requireCollegeId();
        csvParserService.validateCsvFormat(file, "TRAINER");

        try {
            byte[] data = file.getBytes();
            BulkUploadResponse response = bulkUploadService.startTrainerUpload(
                    data,
                    file.getOriginalFilename(),
                    collegeId,
                    user.getId()
            );
            return ResponseEntity.accepted().body(response);
        } catch (Exception e) {
            throw new BadRequestException("Failed to read upload file: " + e.getMessage());
        }
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
    public ResponseEntity<List<BulkUploadHistoryDTO>> getUploadHistory() {
        return ResponseEntity.ok(bulkUploadService.getHistory(SecurityUtils.requireCollegeId(), "STUDENT")
                .stream().map(BulkUploadHistoryDTO::from).toList());
    }

    @GetMapping("/trainers/bulk-upload/history")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<BulkUploadHistoryDTO>> getTrainerUploadHistory() {
        return ResponseEntity.ok(bulkUploadService.getHistory(SecurityUtils.requireCollegeId(), "TRAINER")
                .stream().map(BulkUploadHistoryDTO::from).toList());
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
