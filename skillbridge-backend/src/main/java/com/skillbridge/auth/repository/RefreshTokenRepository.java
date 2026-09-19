package com.skillbridge.auth.repository;

import com.skillbridge.auth.entity.RefreshToken;
import com.skillbridge.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** In any state: a revoked token presented again is exactly what reuse detection looks for. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    long deleteByUserAndExpiresAtBefore(User user, LocalDateTime cutoff);

    /**
     * Revokes one token if, and only if, it is still active, and says whether this
     * call did it. This is the rotation's lock: of two concurrent refreshes that
     * present the same token, exactly one sees 1 and proceeds; the other sees 0.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revoked = true, t.revokedAt = :now WHERE t.id = :id AND t.revoked = false")
    int revokeIfActive(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revoked = true, t.revokedAt = :now WHERE t.familyId = :familyId AND t.revoked = false")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") LocalDateTime now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revoked = true, t.revokedAt = :now WHERE t.user.id = :userId AND t.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
