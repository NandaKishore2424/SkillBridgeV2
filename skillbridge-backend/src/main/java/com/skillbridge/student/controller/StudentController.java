package com.skillbridge.student.controller;

import com.skillbridge.common.idempotency.Idempotent;
import com.skillbridge.student.dto.*;
import com.skillbridge.common.api.DeprecatedEndpoint;
import com.skillbridge.student.dto.SkillDTO;
import com.skillbridge.student.entity.Skill;
import com.skillbridge.student.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;

@RestController
@RequestMapping("/api/v1/students")
@RequiredArgsConstructor
@Slf4j
public class StudentController {
    private final StudentService studentService;

    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentDTO> getMyProfile() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        StudentDTO student = studentService.getStudentProfile(user.getId());
        return ResponseEntity.ok(student);
    }

    /**
     * A student by id.
     *
     * <p><b>STUDENT was removed from the guard on 2026-09-06.</b> The tenant
     * check passes for any student in the same college, so with STUDENT allowed
     * every student could read every classmate's full name, email address and
     * roll number by walking ids. Verified against live data before the change.
     * A student reads their own record through {@code GET /students/me}.
     *
     * <p>Duplicated by {@code GET /admin/students/{id}}, which has the same body
     * and the correct guard, so this one is on its way out.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'TRAINER')")
    @DeprecatedEndpoint(
            since = "2026-09-06",
            sunset = "2026-12-31",
            replacement = "/api/v1/admin/students/{id}",
            reason = "Duplicate of the admin endpoint; this one shipped with a role guard that let any student read any classmate.")
    public ResponseEntity<StudentDTO> getStudentById(@PathVariable Long id) {
        StudentDTO student = studentService.getStudentById(id);
        return ResponseEntity.ok(student);
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentDTO> updateMyProfile(@RequestBody UpdateStudentProfileRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        StudentDTO updated = studentService.updateStudentProfile(user.getId(), request);
        return ResponseEntity.ok(updated);
    }

    /**
     * Complete student profile setup
     * Called when student first logs in with PENDING_SETUP status
     * 
     * @param profileData Complete profile data from setup wizard
     * @return StudentProfileDTO with updated profile
     */
    @PutMapping("/profile/complete")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentProfileDTO> completeProfile(
            @Valid @RequestBody StudentProfileUpdateDTO profileData) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);

        log.info("Profile completion request received for user: {}", user.getEmail());

        StudentProfileDTO profile = studentService.completeProfile(user.getId(), profileData);

        return ResponseEntity.ok(profile);
    }

    @PostMapping("/me/skills")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> addSkill(@RequestBody AddStudentSkillRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        studentService.addSkill(user.getId(), request);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/me/skills/{skillId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> updateSkillProficiency(
            @PathVariable Long skillId,
            @RequestBody Map<String, Integer> request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        studentService.updateSkillProficiency(user.getId(), skillId, request.get("proficiencyLevel"));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/me/skills/{skillId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> removeSkill(@PathVariable Long skillId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        studentService.removeSkill(user.getId(), skillId);
        return ResponseEntity.ok().build();
    }

    // Idempotent: student_projects has no unique constraint of any kind, so a
    // double-clicked Create button leaves two identical rows with nothing to
    // tell them apart afterwards. Requires an Idempotency-Key header.
    @Idempotent
    @PostMapping("/me/projects")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentProjectDTO> addProject(@RequestBody CreateStudentProjectRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        StudentProjectDTO project = studentService.addProject(user.getId(), request);
        return ResponseEntity.ok(project);
    }

    @DeleteMapping("/me/projects/{projectId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> deleteProject(@PathVariable Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        studentService.deleteProject(user.getId(), projectId);
        return ResponseEntity.ok().build();
    }

    /**
     * The skill catalogue.
     *
     * <p>Paged: a platform-wide master table with no tenant predicate, shared
     * by every college.
     */
    @GetMapping("/skills")
    public ResponseEntity<PagedResponse<SkillDTO>> getAllSkills(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(
                studentService.getAllSkills(Pagination.of(page, size)), SkillDTO::from));
    }

    @GetMapping("/skills/search")
    public ResponseEntity<PagedResponse<SkillDTO>> searchSkills(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(
                studentService.searchSkills(q, Pagination.of(page, size)), SkillDTO::from));
    }
}
