package com.skillbridge.auth.service;

import com.skillbridge.auth.dto.AuthResponse;
import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.dto.UserDto;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RoleRepository;
import com.skillbridge.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    
    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.debug("Attempting login for email: {}", request.getEmail());
        
        // Find user by email
        User user = userRepository.findByEmail(request.getEmail())
            .orElseThrow(() -> {
                log.warn("Login failed: User not found with email: {}", request.getEmail());
                return new RuntimeException("Invalid email or password");
            });
        
        // Check if user is active
        if (!user.getIsActive()) {
            log.warn("Login failed: User account is inactive for email: {}", request.getEmail());
            throw new RuntimeException("Account is inactive");
        }
        
        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Login failed: Invalid password for email: {}", request.getEmail());
            throw new RuntimeException("Invalid email or password");
        }
        
        // Get primary role (first role, or SYSTEM_ADMIN if no roles)
        String primaryRole = user.getRoles().stream()
            .findFirst()
            .map(role -> role.getName())
            .orElse("SYSTEM_ADMIN");
        
        log.info("Login successful for user: {} with role: {}", user.getEmail(), primaryRole);
        
        // For now, return a simple token. JWT implementation can be added later
        // In production, you should generate proper JWT tokens here
        String simpleToken = "token_" + user.getId() + "_" + System.currentTimeMillis();
        
        // Build user DTO matching frontend format
        UserDto userDto = UserDto.builder()
            .id(user.getId())
            .email(user.getEmail())
            .role(primaryRole)
            .collegeId(user.getCollegeId())
            .isActive(user.getIsActive())
            .build();
        
        return AuthResponse.builder()
            .accessToken(simpleToken)
            .refreshToken("refresh_" + user.getId())
            .expiresIn(3600L) // 1 hour
            .user(userDto)
            .build();
    }
    
    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        log.debug("Attempting token refresh");
        
        // Extract user ID from refresh token (format: "refresh_{userId}")
        if (!refreshToken.startsWith("refresh_")) {
            log.warn("Invalid refresh token format");
            throw new RuntimeException("Invalid refresh token");
        }
        
        try {
            Long userId = Long.parseLong(refreshToken.substring(8)); // Skip "refresh_"
            
            // Find user
            User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("User not found for refresh token");
                    return new RuntimeException("Invalid refresh token");
                });
            
            // Check if user is active
            if (!user.getIsActive()) {
                log.warn("User account is inactive");
                throw new RuntimeException("Account is inactive");
            }
            
            // Get primary role
            String primaryRole = user.getRoles().stream()
                .findFirst()
                .map(role -> role.getName())
                .orElse("SYSTEM_ADMIN");
            
            // Generate new access token
            String newAccessToken = "token_" + user.getId() + "_" + System.currentTimeMillis();
            
            // Build user DTO
            UserDto userDto = UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(primaryRole)
                .collegeId(user.getCollegeId())
                .isActive(user.getIsActive())
                .build();
            
            log.info("Token refresh successful for user: {}", user.getEmail());
            
            return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(refreshToken) // Keep same refresh token
                .expiresIn(3600L)
                .user(userDto)
                .build();
        } catch (NumberFormatException e) {
            log.warn("Invalid refresh token format: {}", e.getMessage());
            throw new RuntimeException("Invalid refresh token");
        }
    }
}

