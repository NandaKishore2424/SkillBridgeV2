package com.skillbridge.student.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.student.dto.StudentDTO;
import com.skillbridge.student.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/students")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class StudentAdminController {
    private final StudentService studentService;

    @GetMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<StudentDTO>> getAllStudents() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        List<StudentDTO> students = studentService.getAllStudentsByCollege(user.getCollegeId());
        return ResponseEntity.ok(students);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<StudentDTO> getStudentById(@PathVariable Long id) {
        StudentDTO student = studentService.getStudentById(id);
        return ResponseEntity.ok(student);
    }
}
