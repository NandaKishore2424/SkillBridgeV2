package com.skillbridge.student.controller;

import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.student.dto.StudentDTO;
import com.skillbridge.student.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import jakarta.validation.Valid;
import com.skillbridge.common.exception.BadRequestException;
import com.skillbridge.student.dto.UpdateStudentAdminRequest;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;


@RestController
@RequestMapping("/api/v1/admin/students")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class StudentAdminController {
    private final StudentService studentService;

    @GetMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<StudentDTO>> getAllStudents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        Page<StudentDTO> students = studentService.getStudentsByCollege(user.getCollegeId(), PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.<StudentDTO>builder()
                .items(students.getContent())
                .page(students.getNumber())
                .size(students.getSize())
                .totalElements(students.getTotalElements())
                .totalPages(students.getTotalPages())
                .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<StudentDTO> getStudentById(@PathVariable Long id) {
        StudentDTO student = studentService.getStudentById(id);
        return ResponseEntity.ok(student);
    }

    /**
     * Admin edit of a student's academic details.
     * PUT /api/v1/admin/students/{id}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<StudentDTO> updateStudent(
            @PathVariable Long id,
            @Valid @RequestBody UpdateStudentAdminRequest request) {
        return ResponseEntity.ok(studentService.updateStudentAsAdmin(id, request));
    }

    /**
     * Activate or deactivate a student's account.
     * PATCH /api/v1/admin/students/{id}/status
     */
    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Void> updateStudentStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> request) {
        Boolean isActive = request.get("isActive");
        if (isActive == null) {
            throw new BadRequestException("isActive is required");
        }
        studentService.updateStudentStatus(id, isActive);
        return ResponseEntity.noContent().build();
    }
}
