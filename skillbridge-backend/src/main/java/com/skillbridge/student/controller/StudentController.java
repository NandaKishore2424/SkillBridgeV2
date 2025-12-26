package com.skillbridge.student.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.student.dto.*;
import com.skillbridge.student.entity.Skill;
import com.skillbridge.student.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/students")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class StudentController {
    private final StudentService studentService;

    @GetMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentDTO> getMyProfile() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        StudentDTO student = studentService.getStudentProfile(user.getId());
        return ResponseEntity.ok(student);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN') or hasRole('TRAINER') or hasRole('STUDENT')")
    public ResponseEntity<StudentDTO> getStudentById(@PathVariable Long id) {
        StudentDTO student = studentService.getStudentById(id);
        return ResponseEntity.ok(student);
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentDTO> updateMyProfile(@RequestBody UpdateStudentProfileRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        StudentDTO updated = studentService.updateStudentProfile(user.getId(), request);
        return ResponseEntity.ok(updated);
    }

    @PostMapping("/me/skills")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> addSkill(@RequestBody AddStudentSkillRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        studentService.addSkill(user.getId(), request);
        return ResponseEntity.ok().build();
    }

    @PutMapping("/me/skills/{skillId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> updateSkillProficiency(
            @PathVariable Long skillId,
            @RequestBody Map<String, Integer> request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        studentService.updateSkillProficiency(user.getId(), skillId, request.get("proficiencyLevel"));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/me/skills/{skillId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> removeSkill(@PathVariable Long skillId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        studentService.removeSkill(user.getId(), skillId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/me/projects")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentProjectDTO> addProject(@RequestBody CreateStudentProjectRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        StudentProjectDTO project = studentService.addProject(user.getId(), request);
        return ResponseEntity.ok(project);
    }

    @DeleteMapping("/me/projects/{projectId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Void> deleteProject(@PathVariable Long projectId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        studentService.deleteProject(user.getId(), projectId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/skills")
    public ResponseEntity<List<Skill>> getAllSkills() {
        return ResponseEntity.ok(studentService.getAllSkills());
    }

    @GetMapping("/skills/search")
    public ResponseEntity<List<Skill>> searchSkills(@RequestParam String q) {
        return ResponseEntity.ok(studentService.searchSkills(q));
    }
}
