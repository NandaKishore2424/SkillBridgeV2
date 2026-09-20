package com.skillbridge.student.controller;

import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.student.dto.StudentDTO;
import com.skillbridge.student.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import com.skillbridge.common.dto.SortParameter;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.CollegeAdminOnly;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.skillbridge.common.tenant.SoftDeleteService;
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
public class StudentAdminController {
    private final StudentService studentService;
    private final SoftDeleteService softDeleteService;

    /**
     * Student fields a client may sort by, mapped to entity paths.
     *
     * <p>Anything not listed is a 400 naming these, rather than a 500 from deep
     * inside the query — see {@link SortParameter}.
     */
    private static final Map<String, String> SORTABLE = SortParameter.allow(
            "fullName", "fullName",
            "rollNumber", "rollNumber",
            "email", "user.email",
            "degree", "degree",
            "branch", "branch",
            "year", "year",
            "createdAt", "createdAt");

    @GetMapping
    @CollegeAdminOnly
    public ResponseEntity<PagedResponse<StudentDTO>> getAllStudents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String sort
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        Page<StudentDTO> students = studentService.getStudentsByCollege(
                user.getCollegeId(), search, active,
                Pagination.of(page, size, SortParameter.parse(sort, SORTABLE, Sort.by("fullName"))));
        return ResponseEntity.ok(PagedResponse.from(students));
    }

    @GetMapping("/{id}")
    @CollegeAdminOnly
    public ResponseEntity<StudentDTO> getStudentById(@PathVariable Long id) {
        StudentDTO student = studentService.getStudentById(id);
        return ResponseEntity.ok(student);
    }

    /**
     * Admin edit of a student's academic details.
     * PUT /api/v1/admin/students/{id}
     */
    @PutMapping("/{id}")
    @CollegeAdminOnly
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
    @CollegeAdminOnly
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

    /**
     * Soft-delete this student.
     *
     * <p>Marks {@code deleted_at} rather than removing the row: the audit
     * trail, enrollments and progress history all reference it, and a hard
     * delete would take them with it. Hidden from every read afterwards by the
     * {@code activeFilter}.
     *
     * <p>Refused with 409 STUDENT_HAS_ENROLLMENTS while the student is in an active batch. Also deactivates the login.
     */
    @DeleteMapping("/{id}")
    @CollegeAdminOnly
    public ResponseEntity<Void> deleteStudent(@PathVariable Long id, Authentication auth) {
        softDeleteService.deleteStudent(id, SecurityUtils.requirePrincipal(auth).getId());
        return ResponseEntity.noContent().build();
    }
}
