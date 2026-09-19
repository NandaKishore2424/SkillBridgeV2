package com.skillbridge.auth.service;

import com.skillbridge.auth.dto.AuthResponse;
import com.skillbridge.auth.dto.CurrentUserDTO;
import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.dto.UserDto;
import com.skillbridge.auth.entity.RefreshToken;
import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.RefreshTokenRepository;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.PasswordPolicy;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.common.audit.AuditAction;
import com.skillbridge.common.audit.AuditLogService;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.InternalServerException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.exception.UnauthorizedException;
import com.skillbridge.student.entity.Student;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Login, token refresh, logout and password changes.
 *
 * <h2>Refresh tokens</h2>
 * Opaque random values, stored only as SHA-256, sent only in an HttpOnly cookie,
 * and <b>rotated</b> on every use: each refresh revokes the token it was given
 * and issues a successor in the same <b>family</b> (one family per login).
 * <ul>
 *   <li><b>Rotation is atomic.</b> {@code revokeIfActive} is a conditional
 *       UPDATE; of two refreshes presenting the same token at once, exactly one
 *       rotates it and the other is refused.</li>
 *   <li><b>Reuse means theft.</b> A rotated-away token presented again, after
 *       {@link #reuseGrace}, revokes the whole family and every access token the
 *       user holds. The attacker and the victim cannot both be holding a live
 *       successor, and the server cannot tell which is which.</li>
 *   <li><b>The grace period is for tabs, not for attackers.</b> Tabs share the
 *       cookie jar, so two tabs can refresh with the same token a moment apart.
 *       Within the grace period the late one is refused without the family
 *       being revoked, and the SPA picks up the other tab's new token.</li>
 * </ul>
 *
 * <h2>Login failures look alike</h2>
 * An unknown email, a wrong password, and a wrong password for a deactivated
 * account all get the same 401 and roughly the same latency. The unknown-email
 * path runs BCrypt against a dummy hash rather than returning early. "Account is
 * inactive" is said only to someone who proved they know the password.
 */
@Service
@Slf4j
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final TokenRevocationService tokenRevocation;
    private final AuditLogService auditLogService;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final CollegeRepository collegeRepository;

    private final long refreshTokenTtlSeconds;
    private final Duration reuseGrace;
    private final Duration invitationTtl;
    /** A real BCrypt hash of a random value, so an unknown email costs one BCrypt like a known one. */
    private final String dummyHash;

    public AuthService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            JwtService jwtService,
            TokenRevocationService tokenRevocation,
            AuditLogService auditLogService,
            StudentRepository studentRepository,
            TrainerRepository trainerRepository,
            CollegeRepository collegeRepository,
            @Value("${jwt.refreshTokenTtlSeconds:1209600}") long refreshTokenTtlSeconds,
            @Value("${jwt.refreshReuseGraceSeconds:10}") long refreshReuseGraceSeconds,
            @Value("${app.auth.invitation-ttl}") Duration invitationTtl
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtService = jwtService;
        this.tokenRevocation = tokenRevocation;
        this.auditLogService = auditLogService;
        this.studentRepository = studentRepository;
        this.trainerRepository = trainerRepository;
        this.collegeRepository = collegeRepository;
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
        this.reuseGrace = Duration.ofSeconds(refreshReuseGraceSeconds);
        this.invitationTtl = invitationTtl;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** How long the refresh cookie should live: the same as the token behind it. */
    public long refreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    /**
     * An invitation's temporary password travels by email, so it must not work
     * forever: a mailbox read a month later would still open the account.
     * Checked only after the password matched, so it reveals nothing to someone
     * guessing. The admin's remedy is resend-invitation, which restarts the clock.
     */
    private void refuseExpiredInvitation(User user, String auditAction) {
        LocalDateTime sent = user.getInvitationSentAt();
        if (Boolean.TRUE.equals(user.getMustChangePassword()) && sent != null
                && sent.plus(invitationTtl).isBefore(LocalDateTime.now())) {
            auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                    auditAction, AuditAction.OUTCOME_DENIED, "{\"reason\":\"INVITATION_EXPIRED\"}");
            throw new UnauthorizedException(
                    "This invitation has expired. Ask your college admin to send a new one.");
        }
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        if (user == null) {
            // Same work as a wrong password, so timing does not reveal which emails exist.
            passwordEncoder.matches(request.getPassword(), dummyHash);
            auditLogService.recordAnonymous(AuditAction.LOGIN_FAILURE, request.getEmail(),
                    AuditAction.OUTCOME_FAILURE, "{\"reason\":\"NO_SUCH_USER\"}");
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                    AuditAction.LOGIN_FAILURE, AuditAction.OUTCOME_FAILURE, "{\"reason\":\"BAD_PASSWORD\"}");
            throw new UnauthorizedException(INVALID_CREDENTIALS);
        }

        // Only now, to someone who proved they know the password: telling a
        // stranger an account is inactive would confirm that it exists.
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                    AuditAction.LOGIN_FAILURE, AuditAction.OUTCOME_DENIED, "{\"reason\":\"ACCOUNT_INACTIVE\"}");
            throw new UnauthorizedException("Account is inactive");
        }
        refuseExpiredInvitation(user, AuditAction.LOGIN_FAILURE);

        String primaryRole = primaryRoleOf(user);
        log.info("Login successful for user {} with role {}", user.getId(), primaryRole);
        auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                AuditAction.LOGIN_SUCCESS, AuditAction.OUTCOME_SUCCESS, "{\"role\":\"" + primaryRole + "\"}");

        return issueSession(user, UUID.randomUUID());
    }

    // ------------------------------------------------------------------
    // Refresh
    // ------------------------------------------------------------------

    /**
     * Rotates a refresh token. Refusals are 401s thrown AFTER the revocations they
     * cause, so the transaction must not roll those back: {@code noRollbackFor}.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public AuthResponse refreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException("Refresh token is required");
        }

        RefreshToken stored = refreshTokenRepository.findByTokenHash(hashToken(refreshToken))
                .orElseThrow(() -> new UnauthorizedException("Invalid refresh token"));
        LocalDateTime now = LocalDateTime.now();

        if (Boolean.TRUE.equals(stored.getRevoked())) {
            handleReplay(stored, now);
            throw new UnauthorizedException("Invalid refresh token");
        }

        if (stored.getExpiresAt().isBefore(now)) {
            refreshTokenRepository.revokeIfActive(stored.getId(), now);
            throw new UnauthorizedException("Refresh token expired");
        }

        User user = stored.getUser();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new UnauthorizedException("Account is inactive");
        }

        // The lock: whoever flips revoked false -> true owns this rotation.
        if (refreshTokenRepository.revokeIfActive(stored.getId(), now) == 0) {
            log.info("Refresh token {} was rotated by a concurrent request; refusing this one", stored.getId());
            throw new UnauthorizedException("Invalid refresh token");
        }

        return issueSession(user, stored.getFamilyId());
    }

    /**
     * A revoked token came back. Inside the grace period it is a second tab that
     * lost a race: refuse, change nothing. Outside it, someone else holds this
     * family, so end all of it.
     */
    private void handleReplay(RefreshToken stored, LocalDateTime now) {
        LocalDateTime revokedAt = stored.getRevokedAt();
        if (revokedAt != null && !revokedAt.plus(reuseGrace).isBefore(now)) {
            log.info("Refresh token {} replayed {} after rotation, within the grace period; refused, family kept",
                    stored.getId(), Duration.between(revokedAt, now));
            return;
        }
        // Read everything needed first: the bulk UPDATE below clears the
        // persistence context, which would leave the lazy user proxy detached.
        User user = stored.getUser();
        Long userId = user.getId();
        String email = user.getEmail();
        Long collegeId = user.getCollegeId();
        UUID familyId = stored.getFamilyId();

        int revoked = refreshTokenRepository.revokeFamily(familyId, now);
        tokenRevocation.revoke(userId);
        log.warn("Refresh token reuse for user {}: revoked {} token(s) in family {} and every access token",
                userId, revoked, familyId);
        auditLogService.recordFor(userId, email, collegeId,
                AuditAction.REFRESH_TOKEN_REUSE, AuditAction.OUTCOME_DENIED,
                "{\"familyId\":\"" + familyId + "\",\"revoked\":" + revoked + "}");
    }

    // ------------------------------------------------------------------
    // Logout
    // ------------------------------------------------------------------

    /** Ends this login: every token in the presented token's family. Other devices' logins are untouched. */
    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hashToken(refreshToken))
                .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), LocalDateTime.now()));
    }

    // ------------------------------------------------------------------
    // Passwords
    // ------------------------------------------------------------------

    /**
     * Changes the password, ends <b>every</b> session of this user (all refresh
     * tokens, all access tokens), and starts a fresh one for the caller. A
     * password change is what a user does after suspecting compromise, so it has
     * to lock out whoever else is signed in.
     */
    @Transactional
    public AuthResponse changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            auditLogService.record(AuditAction.PASSWORD_CHANGED, "User", userId,
                    AuditAction.OUTCOME_FAILURE, "{\"reason\":\"BAD_OLD_PASSWORD\"}");
            throw new UnauthorizedException("Invalid current password");
        }
        PasswordPolicy.check(newPassword, user.getEmail());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        userRepository.save(user);
        revokeEverySession(user);
        auditLogService.record(AuditAction.PASSWORD_CHANGED, "User", userId, AuditAction.OUTCOME_SUCCESS);

        return issueSession(user, UUID.randomUUID());
    }

    @Transactional
    public AuthResponse firstLogin(String email, String temporaryPassword, String newPassword) {
        User user = userRepository.findByEmail(email).orElse(null);
        // An unknown email and a wrong temporary password must look the same:
        // this endpoint is public, and a 404 here enumerated accounts.
        boolean matches = passwordEncoder.matches(temporaryPassword,
                user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !matches) {
            auditLogService.recordAnonymous(AuditAction.FIRST_LOGIN_COMPLETED, email,
                    AuditAction.OUTCOME_FAILURE, "{\"reason\":\"BAD_CREDENTIALS\"}");
            throw new UnauthorizedException("Invalid email or temporary password");
        }

        if (!Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw new BusinessRuleException("User is not required to change password via first-login flow. Use change-password.");
        }
        refuseExpiredInvitation(user, AuditAction.FIRST_LOGIN_COMPLETED);
        PasswordPolicy.check(newPassword, user.getEmail());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setAccountStatus("ACTIVE");
        user.setFirstLoginAt(LocalDateTime.now());
        userRepository.save(user);
        revokeEverySession(user);
        auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                AuditAction.FIRST_LOGIN_COMPLETED, AuditAction.OUTCOME_SUCCESS, null);

        return issueSession(user, UUID.randomUUID());
    }

    private void revokeEverySession(User user) {
        int refreshTokens = refreshTokenRepository.revokeAllForUser(user.getId(), LocalDateTime.now());
        tokenRevocation.revoke(user.getId());
        auditLogService.recordFor(user.getId(), user.getEmail(), user.getCollegeId(),
                AuditAction.SESSIONS_REVOKED, AuditAction.OUTCOME_SUCCESS,
                "{\"refreshTokens\":" + refreshTokens + "}");
    }

    // ------------------------------------------------------------------
    // Current user
    // ------------------------------------------------------------------

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
                .roles(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()))
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

    // ------------------------------------------------------------------
    // Issuing
    // ------------------------------------------------------------------

    /** A new access token plus a new refresh token in {@code familyId}. */
    private AuthResponse issueSession(User user, UUID familyId) {
        String primaryRole = primaryRoleOf(user);
        String accessToken = jwtService.generateAccessToken(user, primaryRole, roleNamesOf(user),
                Boolean.TRUE.equals(user.getMustChangePassword()));
        String refreshToken = issueRefreshToken(user, familyId);

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

    private String issueRefreshToken(User user, UUID familyId) {
        refreshTokenRepository.deleteByUserAndExpiresAtBefore(user, LocalDateTime.now());
        String rawToken = generateSecureToken();
        refreshTokenRepository.save(RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(rawToken))
                .familyId(familyId)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenTtlSeconds))
                .revoked(false)
                .build());
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
    private static Set<String> roleNamesOf(User user) {
        return user.getRoles().stream()
                .map(Role::getName)
                .collect(Collectors.toUnmodifiableSet());
    }
}
