package com.skillbridge.auth.repository;

import com.skillbridge.auth.entity.RefreshToken;
import com.skillbridge.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByTokenHashAndRevokedFalse(String tokenHash);
    long deleteByUser(User user);
    long deleteByUserAndExpiresAtBefore(User user, LocalDateTime cutoff);
}
