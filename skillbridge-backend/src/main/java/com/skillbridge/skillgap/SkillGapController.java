package com.skillbridge.skillgap;

import com.skillbridge.auth.security.SecurityUtils;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.CollegeAdminOnly;
import com.skillbridge.common.security.StudentOnly;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI skill-gap report. 204 means no analysis has run yet; a report with
 * status SKIPPED means the student has no skills to analyse.
 */
@RestController
public class SkillGapController {

    private final SkillGapService service;

    public SkillGapController(SkillGapService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/students/me/skill-gap")
    @StudentOnly
    public ResponseEntity<SkillGapReportDTO> myReport() {
        return service.forStudentUser(SecurityUtils.currentUser().getId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** 202: queued. The report changes when the AI service has run, usually within seconds. */
    @PostMapping("/api/v1/students/me/skill-gap/refresh")
    @StudentOnly
    public ResponseEntity<Void> refreshMyReport() {
        service.requestAnalysis(SecurityUtils.currentUser().getId());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/api/v1/admin/students/{studentId}/skill-gap")
    @CollegeAdminOnly
    public ResponseEntity<SkillGapReportDTO> studentReport(@PathVariable Long studentId) {
        return service.forStudent(studentId, SecurityUtils.requireCollegeId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
