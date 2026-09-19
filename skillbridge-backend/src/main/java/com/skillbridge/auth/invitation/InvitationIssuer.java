package com.skillbridge.auth.invitation;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.security.TemporaryPasswordGenerator;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.tenant.TenantGuard;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * The database half of an invitation: sets a fresh temporary password. Sending
 * it is {@link InvitationService}'s job, after this commits.
 */
@Service
public class InvitationIssuer {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public InvitationIssuer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * A new temporary password for an account that has not completed first
     * login. It replaces the previous one, and restarts the invitation's
     * lifetime ({@code app.auth.invitation-ttl}).
     *
     * <p>Guarded on {@code PENDING_SETUP}, so this cannot reset the password of
     * an account in use.
     */
    @Transactional
    public IssuedInvitation reissue(Long userId, String expectedRole) {
        // A user of another college, or of a different kind than the path says,
        // is indistinguishable from a missing one. Before 2026-09-19 this loaded
        // any user by id, so a college admin could reset another college's pending
        // invitation, or, through /students/{id}, a fellow admin's.
        User user = userRepository.findById(userId)
                .filter(u -> TenantGuard.isVisible(u.getCollegeId()))
                .filter(u -> u.getRoles().stream().anyMatch(r -> expectedRole.equals(r.getName())))
                .orElseThrow(() -> ResourceNotFoundException.of("User", userId));

        if (!"PENDING_SETUP".equals(user.getAccountStatus())) {
            throw new ConflictException("User is already active or not in pending state");
        }

        String temporaryPassword = TemporaryPasswordGenerator.generate();
        user.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        user.setMustChangePassword(true);
        user.setInvitationSentAt(LocalDateTime.now());
        userRepository.save(user);
        return new IssuedInvitation(user.getEmail(), temporaryPassword);
    }
}
