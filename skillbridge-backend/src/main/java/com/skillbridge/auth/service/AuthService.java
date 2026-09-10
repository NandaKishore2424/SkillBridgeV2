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
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.InternalServerException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.auth.dto.CurrentUserDTO;
import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import com.skillbridge.common.audit.AuditAction;
import com.skillbridge.common.audit.AuditLogService;
import com.skillbridge.common.exception.UnauthorizedException;

@Service
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final AuditLogService auditLogService;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final CollegeRepository collegeRepository;

    private final long refreshTokenTtlSeconds;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            AuditLogService auditLogService,
            StudentRepository studentRepository,
            TrainerRepository trainerRepository,
            CollegeRepository collegeRepository,
            @Value("${jwt.refreshTokenTtlSeconds:1209600}") long refreshTokenTtlSeconds
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.auditLogService = auditLogService;
        this.studentRepository = studentRepository;
        this.trainerRepository = trainerRepository;
        this.collegeRepository = collegeRepository;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        log.debug("Attempting login for email: {}", request.getEmail());

        // Find user by email
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    log.warn("Login failed: User not found with email: {}", request.getEmail());
                    // Recorded with a null actorUserId: there is no account, and
                    // repeated misses against invented addresses are themselves
                    // the signal worth seeing.
                    auditLogService.recordAnonymous(AuditAction.LOGIN_FAILURE, request.getEmail(),
                            AuditAction.OUTCOME_FAILURE, "{\"reason\":\"NO_SUCH_USER\"}");
                    return new UnauthorizedException("Invalid email or password");
                });

        // Check if user is active
        if (!user.getIsActive()) {
            log.warn("Login failed: User account is inactive for email: {}", request.getEmail());
            // The account is known here, so the row is attributed to it and to
            // its college -- a college admin needs to see failed attempts
            // against their own users, which an anonymous row would hide.
            auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                    AuditAction.LOGIN_FAILURE, AuditAction.OUTCOME_DENIED,
                    "{\"reason\":\"ACCOUNT_INACTIVE\"}");
            throw new UnauthorizedException("Account is inactive");
        }

        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Login failed: Invalid password for email: {}", request.getEmail());
            auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                    AuditAction.LOGIN_FAILURE, AuditAction.OUTCOME_FAILURE,
                    "{\"reason\":\"BAD_PASSWORD\"}");
            throw new UnauthorizedException("Invalid email or password");
        }

        String primaryRole = primaryRoleOf(user);

        log.info("Login successful for user: {} with role: {}", user.getEmail(), primaryRole);
        auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                AuditAction.LOGIN_SUCCESS, AuditAction.OUTCOME_SUCCESS,
                "{\"role\":\"" + primaryRole + "\"}");

        String accessToken = jwtService.generateAccessToken(user, primaryRole, roleNamesOf(user),
                Boolean.TRUE.equals(user.getMustChangePassword()));
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
            .expiresIn(jwtService.accessTokenTtlSeconds())
                .user(userDto)
                .build();
    }

    @Transactional
    public AuthResponse refreshToken(String refreshToken) {
        log.debug("Attempting token refresh");
        if (refreshToken == null || refreshToken.isBlank()) {
            log.warn("Missing refresh token");
            throw new UnauthorizedException("Refresh token is required");
        }

        String tokenHash = hashToken(refreshToken);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));

        if (storedToken.getExpiresAt().isBefore(LocalDateTime.now())) {
            storedToken.setRevoked(true);
            refreshTokenRepository.save(storedToken);
            throw new UnauthorizedException("Refresh token expired");
        }

        User user = storedToken.getUser();
        if (!user.getIsActive()) {
            log.warn("User account is inactive");
            throw new UnauthorizedException("Account is inactive");
        }

        String primaryRole = primaryRoleOf(user);

        String newAccessToken = jwtService.generateAccessToken(user, primaryRole, roleNamesOf(user),
                Boolean.TRUE.equals(user.getMustChangePassword()));
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
                .expiresIn(jwtService.accessTokenTtlSeconds())
                .user(userDto)
                .build();
    }

    /**
     * Describes the authenticated caller.
     *
     * <p>The profile lookup is chosen by role rather than attempted three times:
     * a student has no trainer row and vice versa, so speculative queries would
     * be two guaranteed misses per call on the most frequently hit endpoint in
     * any SPA.
     *
     * <p>Admins have neither profile, so {@code fullName} and {@code profileId}
     * are null for them. That is the honest answer -- the account genuinely has
     * no display name -- and is why the UI falls back to the email local part.
     */
    @Transactional(readOnly = true)
    public CurrentUserDTO describeCurrentUser(AuthenticatedUser caller) {
        User user = userRepository.findById(caller.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("User", caller.getId()));

        String primaryRole = user.getRoles().stream()
                .findFirst().map(Role::getName).orElse(null);

        String fullName = null;
        Long profileId = null;
        if ("STUDENT".equals(primaryRole)) {
            Student student = studentRepository.findByUser_Id(user.getId()).orElse(null);
            if (student != null) {
                fullName = student.getFullName();
                profileId = student.getId();
            }
        } else if ("TRAINER".equals(primaryRole)) {
            Trainer trainer = trainerRepository.findByUser_Id(user.getId()).orElse(null);
            if (trainer != null) {
                fullName = trainer.getFullName();
                profileId = trainer.getId();
            }
        }

        String collegeName = user.getCollegeId() == null ? null
                : collegeRepository.findById(user.getCollegeId()).map(College::getName).orElse(null);

        return CurrentUserDTO.builder()
                .id(user.getId())
                .email(user.getEmail())
                .roles(user.getRoles().stream().map(Role::getName).collect(java.util.stream.Collectors.toSet()))
                .primaryRole(primaryRole)
                .collegeId(user.getCollegeId())
                .collegeName(collegeName)
                .fullName(fullName)
                .profileId(profileId)
                .profileCompleted(Boolean.TRUE.equals(user.getProfileCompleted()))
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .accountStatus(user.getAccountStatus())
                .build();
    }

    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            auditLogService.record(AuditAction.PASSWORD_CHANGED, "User", userId,
                    AuditAction.OUTCOME_FAILURE, "{\"reason\":\"BAD_OLD_PASSWORD\"}");
            throw new UnauthorizedException("Invalid old password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        auditLogService.record(AuditAction.PASSWORD_CHANGED, "User", userId,
                AuditAction.OUTCOME_SUCCESS);
    }

    @Transactional
    public AuthResponse firstLogin(String email, String temporaryPassword, String newPassword) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!passwordEncoder.matches(temporaryPassword, user.getPasswordHash())) {
            auditLogService.recordAnonymous(AuditAction.FIRST_LOGIN_COMPLETED, email,
                    AuditAction.OUTCOME_FAILURE, "{\"reason\":\"BAD_TEMPORARY_PASSWORD\"}");
            throw new UnauthorizedException("Invalid temporary password");
        }

        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw new BusinessRuleException("User is not required to change password via first-login flow. Use change-password.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setAccountStatus("ACTIVE");
        user.setFirstLoginAt(java.time.LocalDateTime.now());
        userRepository.save(user);
        auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                AuditAction.FIRST_LOGIN_COMPLETED, AuditAction.OUTCOME_SUCCESS, null);

        String primaryRole = primaryRoleOf(user);

        String accessToken = jwtService.generateAccessToken(user, primaryRole, roleNamesOf(user),
                Boolean.TRUE.equals(user.getMustChangePassword()));
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
                .expiresIn(jwtService.accessTokenTtlSeconds())
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
            throw new InternalServerException("Failed to hash token");
        }
    }

    /**
     * The role name to put in the token's {@code role} claim.
     *
     * <p><b>A user with no roles gets none.</b> All three call sites used to end
     * {@code .orElse("SYSTEM_ADMIN")}, which handed the highest privilege in the
     * system to any account whose roles had not been set — a bulk-provisioned
     * user mid-setup, or one whose {@code user_roles} rows were removed.
     *
     * <p>It was inert while the authentication filter derived authorities from
     * the database and ignored this claim. It stopped being inert the moment the
     * filter began trusting claims, which is why it is fixed here rather than
     * noted: the change that made the token authoritative is the change that
     * would have made this exploitable.
     */
    private static String primaryRoleOf(User user) {
        return user.getRoles().stream()
                .findFirst()
                .map(Role::getName)
                .orElseThrow(() -> {
                    log.error("User {} has no roles; refusing to issue a token rather than "
                            + "guessing a privilege level", user.getId());
                    return new UnauthorizedException("This account has no role assigned. "
                            + "Contact your administrator.");
                });
    }

    /** Every role name, for the token's {@code roles} claim. */
    private static java.util.Set<String> roleNamesOf(User user) {
        return user.getRoles().stream()
                .map(Role::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

}
