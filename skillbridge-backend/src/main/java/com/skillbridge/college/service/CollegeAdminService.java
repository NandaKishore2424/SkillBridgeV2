package com.skillbridge.college.service;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RoleRepository;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class CollegeAdminService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final CollegeRepository collegeRepository;
    private final CollegeAdminRepository collegeAdminRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public CollegeAdmin createCollegeAdmin(Long collegeId, CreateCollegeAdminRequest request) {
        log.info("Creating college admin for college {} with email: {}", collegeId, request.email);

        // Check if college exists
        College college = collegeRepository.findById(collegeId)
            .orElseThrow(() -> new RuntimeException("College not found with id: " + collegeId));

        // Check if email already exists
        if (userRepository.existsByEmail(request.email)) {
            throw new RuntimeException("User with email already exists: " + request.email);
        }

        // Get COLLEGE_ADMIN role - find by enum and convert to string
        Role collegeAdminRole = roleRepository.findAll().stream()
            .filter(role -> "COLLEGE_ADMIN".equals(role.getName()))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("COLLEGE_ADMIN role not found"));

        // Create user
        User user = User.builder()
            .email(request.email)
            .passwordHash(passwordEncoder.encode(request.password))
            .collegeId(collegeId)
            .isActive(true)
            .build();

        // Assign role
        Set<Role> roles = new HashSet<>();
        roles.add(collegeAdminRole);
        user.setRoles(roles);

        User savedUser = userRepository.save(user);
        log.info("Created user with id: {} for college admin", savedUser.getId());

        // Create college admin profile
        CollegeAdmin collegeAdmin = CollegeAdmin.builder()
            .user(savedUser)
            .college(college)
            .fullName(request.fullName)
            .phone(request.phone)
            .build();

        CollegeAdmin savedAdmin = collegeAdminRepository.save(collegeAdmin);
        log.info("Created college admin with id: {} for college {}", savedAdmin.getId(), collegeId);

        return savedAdmin;
    }

    public static class CreateCollegeAdminRequest {
        public String email;
        public String password;
        public String fullName;
        public String phone;
    }
}

