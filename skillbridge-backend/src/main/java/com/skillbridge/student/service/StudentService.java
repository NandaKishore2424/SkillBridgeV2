package com.skillbridge.student.service;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RoleRepository;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.student.dto.*;
import com.skillbridge.student.entity.*;
import com.skillbridge.student.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudentService {
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final CollegeRepository collegeRepository;
    private final SkillRepository skillRepository;
    private final StudentSkillRepository studentSkillRepository;
    private final StudentProjectRepository studentProjectRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public StudentDTO createStudent(CreateStudentRequest request) {
        // Validate college
        College college = collegeRepository.findById(request.getCollegeId())
                .orElseThrow(() -> new RuntimeException("College not found"));

        // Check if roll number already exists for this college
        if (studentRepository.existsByRollNumberAndCollegeId(request.getRollNumber(), request.getCollegeId())) {
            throw new RuntimeException("Roll number already exists for this college");
        }

        // Check if user with email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("User with this email already exists");
        }

        // Get STUDENT role
        Role studentRole = roleRepository.findByName("STUDENT")
                .orElseThrow(() -> new RuntimeException("Required role not found"));

        Set<Role> roles = new HashSet<>();
        roles.add(studentRole);

        // Create User
        User newUser = User.builder()
                .collegeId(request.getCollegeId())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .isActive(true)
                .roles(roles)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        User savedUser = userRepository.save(newUser);

        // Create Student profile
        Student student = Student.builder()
                .user(savedUser)
                .college(college)
                .fullName(request.getFullName())
                .rollNumber(request.getRollNumber())
                .degree(request.getDegree())
                .branch(request.getBranch())
                .year(request.getYear())
                .phone(request.getPhone())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        Student savedStudent = studentRepository.save(student);

        return mapToDTO(savedStudent);
    }

    public StudentDTO getStudentProfile(Long userId) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));
        return mapToDTO(student);
    }

    public StudentDTO getStudentById(Long studentId) {
        Student student = studentRepository.findById(studentId)
                .orElseThrow(() -> new RuntimeException("Student not found"));
        return mapToDTO(student);
    }

    public List<StudentDTO> getAllStudentsByCollege(Long collegeId) {
        return studentRepository.findByCollegeId(collegeId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    public Page<StudentDTO> getStudentsByCollege(Long collegeId, Pageable pageable) {
        return studentRepository.findByCollegeId(collegeId, pageable)
                .map(this::mapToDTO);
    }

    @Transactional
    public StudentDTO updateStudentProfile(Long userId, UpdateStudentProfileRequest request) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));

        if (request.getFullName() != null)
            student.setFullName(request.getFullName());
        if (request.getPhone() != null)
            student.setPhone(request.getPhone());
        if (request.getDegree() != null)
            student.setDegree(request.getDegree());
        if (request.getBranch() != null)
            student.setBranch(request.getBranch());
        if (request.getYear() != null)
            student.setYear(request.getYear());
        if (request.getGithubUrl() != null)
            student.setGithubUrl(request.getGithubUrl());
        if (request.getPortfolioUrl() != null)
            student.setPortfolioUrl(request.getPortfolioUrl());
        if (request.getBio() != null)
            student.setBio(request.getBio());

        student.setUpdatedAt(LocalDateTime.now());
        Student updated = studentRepository.save(student);
        return mapToDTO(updated);
    }

    /**
     * Complete student profile setup - called when student first logs in with
     * PENDING_SETUP status
     * Updates student profile data and changes user account status to ACTIVE
     * 
     * @param userId      User ID of the student
     * @param profileData Complete profile data from setup wizard
     * @return StudentProfileDTO with updated profile information
     * @throws RuntimeException if student not found or profile already completed
     */
    @Transactional
    public StudentProfileDTO completeProfile(Long userId, StudentProfileUpdateDTO profileData) {
        log.info("Completing profile for user ID: {}", userId);

        // Fetch student and user entities
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));

        User user = student.getUser();

        // Validate that profile is not already completed
        if (user.getProfileCompleted() != null && user.getProfileCompleted()) {
            log.warn("Profile already completed for user: {}", user.getEmail());
            throw new RuntimeException("Profile has already been completed");
        }

        // Update student profile fields
        student.setFullName(profileData.getFullName());
        student.setPhone(profileData.getPhone());
        student.setDegree(profileData.getDegree());
        student.setBranch(profileData.getBranch());
        student.setYear(profileData.getYear());
        student.setRollNumber(profileData.getRollNumber());

        // Optional fields - only set if provided
        if (profileData.getBio() != null && !profileData.getBio().trim().isEmpty()) {
            student.setBio(profileData.getBio());
        }
        if (profileData.getGithubUrl() != null && !profileData.getGithubUrl().trim().isEmpty()) {
            student.setGithubUrl(profileData.getGithubUrl());
        }
        if (profileData.getPortfolioUrl() != null && !profileData.getPortfolioUrl().trim().isEmpty()) {
            student.setPortfolioUrl(profileData.getPortfolioUrl());
        }
        if (profileData.getResumeUrl() != null && !profileData.getResumeUrl().trim().isEmpty()) {
            student.setResumeUrl(profileData.getResumeUrl());
        }

        student.setUpdatedAt(LocalDateTime.now());

        // Update user account status
        user.setAccountStatus("ACTIVE");
        user.setProfileCompleted(true);
        user.setUpdatedAt(LocalDateTime.now());

        // Save both entities (cascade should handle this, but being explicit)
        userRepository.save(user);
        Student savedStudent = studentRepository.save(student);

        log.info("Profile completed successfully for user: {} ({})", user.getEmail(), userId);

        // Map to response DTO
        return StudentProfileDTO.builder()
                .id(savedStudent.getId())
                .userId(user.getId())
                .collegeId(savedStudent.getCollege().getId())
                .fullName(savedStudent.getFullName())
                .rollNumber(savedStudent.getRollNumber())
                .degree(savedStudent.getDegree())
                .branch(savedStudent.getBranch())
                .year(savedStudent.getYear())
                .phone(savedStudent.getPhone())
                .githubUrl(savedStudent.getGithubUrl())
                .portfolioUrl(savedStudent.getPortfolioUrl())
                .resumeUrl(savedStudent.getResumeUrl())
                .bio(savedStudent.getBio())
                .accountStatus(user.getAccountStatus())
                .profileCompleted(user.getProfileCompleted())
                .createdAt(savedStudent.getCreatedAt())
                .updatedAt(savedStudent.getUpdatedAt())
                .build();
    }

    @Transactional
    public void addSkill(Long userId, AddStudentSkillRequest request) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));

        Skill skill = skillRepository.findById(request.getSkillId())
                .orElseThrow(() -> new RuntimeException("Skill not found"));

        // Check if skill already exists
        StudentSkillId skillId = new StudentSkillId(student.getId(), skill.getId());
        if (studentSkillRepository.existsById(skillId)) {
            throw new RuntimeException("Skill already added");
        }

        StudentSkill studentSkill = StudentSkill.builder()
                .id(skillId)
                .student(student)
                .skill(skill)
                .proficiencyLevel(request.getProficiencyLevel())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        studentSkillRepository.save(studentSkill);
    }

    @Transactional
    public void updateSkillProficiency(Long userId, Long skillId, Integer proficiencyLevel) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));

        StudentSkillId id = new StudentSkillId(student.getId(), skillId);
        StudentSkill studentSkill = studentSkillRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Skill not found"));

        studentSkill.setProficiencyLevel(proficiencyLevel);
        studentSkill.setUpdatedAt(LocalDateTime.now());
        studentSkillRepository.save(studentSkill);
    }

    @Transactional
    public void removeSkill(Long userId, Long skillId) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));

        studentSkillRepository.deleteByStudentIdAndSkillId(student.getId(), skillId);
    }

    @Transactional
    public StudentProjectDTO addProject(Long userId, CreateStudentProjectRequest request) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));

        StudentProject project = StudentProject.builder()
                .student(student)
                .title(request.getTitle())
                .description(request.getDescription())
                .technologies(request.getTechnologies())
                .projectUrl(request.getProjectUrl())
                .githubUrl(request.getGithubUrl())
                .startDate(parseDate(request.getStartDate()))
                .endDate(parseDate(request.getEndDate()))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        StudentProject saved = studentProjectRepository.save(project);
        return mapProjectToDTO(saved);
    }

    @Transactional
    public void deleteProject(Long userId, Long projectId) {
        Student student = studentRepository.findByUser_Id(userId)
                .orElseThrow(() -> new RuntimeException("Student profile not found"));

        StudentProject project = studentProjectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found"));

        if (!project.getStudent().getId().equals(student.getId())) {
            throw new RuntimeException("Unauthorized to delete this project");
        }

        studentProjectRepository.delete(project);
    }

    public List<Skill> getAllSkills() {
        return skillRepository.findAll();
    }

    public List<Skill> searchSkills(String query) {
        return skillRepository.findByNameContainingIgnoreCase(query);
    }

    // Helper methods
    private StudentDTO mapToDTO(Student student) {
        List<StudentSkillDTO> skills = studentSkillRepository.findByStudentId(student.getId()).stream()
                .map(ss -> StudentSkillDTO.builder()
                        .skillId(ss.getSkill().getId())
                        .skillName(ss.getSkill().getName())
                        .skillCategory(ss.getSkill().getCategory())
                        .proficiencyLevel(ss.getProficiencyLevel())
                        .build())
                .collect(Collectors.toList());

        List<StudentProjectDTO> projects = studentProjectRepository.findByStudentIdOrderByStartDateDesc(student.getId())
                .stream()
                .map(this::mapProjectToDTO)
                .collect(Collectors.toList());

        return StudentDTO.builder()
                .id(student.getId())
                .userId(student.getUser().getId())
                .email(student.getUser().getEmail())
                .isActive(student.getUser().getIsActive()) // Added: frontend needs this
                .fullName(student.getFullName())
                .rollNumber(student.getRollNumber())
                .degree(student.getDegree())
                .branch(student.getBranch())
                .year(student.getYear())
                .phone(student.getPhone())
                .githubUrl(student.getGithubUrl())
                .portfolioUrl(student.getPortfolioUrl())
                .resumeUrl(student.getResumeUrl())
                .bio(student.getBio())
                .skills(skills)
                .projects(projects)
                .createdAt(student.getCreatedAt())
                .updatedAt(student.getUpdatedAt())
                .build();
    }

    private StudentProjectDTO mapProjectToDTO(StudentProject project) {
        return StudentProjectDTO.builder()
                .id(project.getId())
                .title(project.getTitle())
                .description(project.getDescription())
                .technologies(project.getTechnologies())
                .projectUrl(project.getProjectUrl())
                .githubUrl(project.getGithubUrl())
                .startDate(project.getStartDate())
                .endDate(project.getEndDate())
                .build();
    }

    private LocalDate parseDate(String dateString) {
        if (dateString == null || dateString.trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dateString);
        } catch (Exception e) {
            log.warn("Failed to parse date: {}", dateString);
            return null;
        }
    }
}
