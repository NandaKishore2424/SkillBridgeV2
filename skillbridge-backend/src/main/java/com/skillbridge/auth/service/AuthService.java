package com.skillbridge.auth.service;

import com.skillbridge.auth.dto.AuthResponse;
import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.dto.UserDto;
import com.skillbridge.auth.entity.RefreshToken;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RefreshTokenRepository;
import com.skillbridge.auth.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;

    private final long refreshTokenTtlSeconds;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            @Value("${jwt.refreshTokenTtlSeconds:1209600}") long refreshTokenTtlSeconds
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

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

        // Get primary role
        String primaryRole = user.getRoles().stream()
                .findFirst()
                .map(role -> role.getName())
                .orElse("SYSTEM_ADMIN");

        log.info("Login successful for user: {} with role: {}", user.getEmail(), primaryRole);

        String accessToken = jwtService.generateAccessToken(user, primaryRole);
        String refreshToken = issueRefreshToken(user);

        UserDto userDto = UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(primaryRole)
                .collegeId(user.getCollegeId())
                .isActive(user.getIsActive())
                .mustChangePassword(user.getMustChangePassword())
                .accountStatus(user.getAccountStatus())
                .profileCompleted(user.getProfileCompleted())
                .build();

        return AuthResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .expiresIn(3600L)
                .user(userDto)
                .build();
    }

    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        log.debug("Attempting token refresh");
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Missing refresh token");
            throw new RuntimeException("Refresh token is required");
        }

        String tokenHash = hashToken(refreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .orElseThrow(() -> new RuntimeException("Invalid refresh token"));

        if (storedToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            storedToken.setRevoked(true);
            refreshTokenRepository.save(storedToken);
            throw new RuntimeException("Refresh token expired");
        }

        User user = storedToken.getUser();
        if (!user.getIsActive()) {
            log.warn("User account is inactive");
            throw new RuntimeException("Account is inactive");
        }

        String primaryRole = user.getRoles().stream()
                .findFirst()
                .map(role -> role.getName())
                .orElse("SYSTEM_ADMIN");

        String newAccessToken = jwtService.generateAccessToken(user, primaryRole);
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        String newRefreshToken = issueRefreshToken(user);

        UserDto userDto = UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(primaryRole)
                .collegeId(user.getCollegeId())
                .isActive(user.getIsActive())
                .mustChangePassword(user.getMustChangePassword())
                .accountStatus(user.getAccountStatus())
                .profileCompleted(user.getProfileCompleted())
                .build();

        log.info("Token refresh successful for user: {}", user.getEmail());

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(3600L)
                .user(userDto)
                .build();
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new RuntimeException("Invalid old password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
    }

    @Transactional
    public AuthResponse firstLogin(String email, String temporaryPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!passwordEncoder.matches(temporaryPassword, user.getPasswordHash())) {
            throw new RuntimeException("Invalid temporary password");
        }

        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw new RuntimeException(
                    "User is not required to change password via first-login flow. Use change-password.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setAccountStatus("ACTIVE");
        user.setFirstLoginAt(java.time.LocalDateTime.now());
        userRepository.save(user);

        String primaryRole = user.getRoles().stream()
                .findFirst()
                .map(role -> role.getName())
                .orElse("SYSTEM_ADMIN");

        String accessToken = jwtService.generateAccessToken(user, primaryRole);
        String refreshToken = issueRefreshToken(user);

        UserDto userDto = UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(primaryRole)
                .collegeId(user.getCollegeId())
                .isActive(user.getIsActive())
                .mustChangePassword(false)
                .accountStatus("ACTIVE")
                .profileCompleted(user.getProfileCompleted())
                .build();

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(3600L)
                .user(userDto)
                .build();
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String tokenHash = hashToken(refreshToken);
        refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .ifPresent(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    private String issueRefreshToken(User user) {
        refreshTokenRepository.deleteByUserAndExpiresAtBefore(user, LocalDateTime.now());
        String rawToken = generateSecureToken();
        RefreshToken refreshToken = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(rawToken))
                .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenTtlSeconds))
                .revoked(false)
                .build();
        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    private String generateSecureToken() {
        byte[] randomBytes = new byte[48];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes) + "_" + UUID.randomUUID();
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to hash token");
        }
    }
}
