package com.skillbridge.common.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    /**
     * Looks up by the columns the unique constraint covers, not including
     * endpoint — a key reused on a different endpoint has to come back so the
     * interceptor can reject it, rather than miss and collide on insert.
     */
    Optional<IdempotencyKey> findByIdempotencyKeyAndUserId(String idempotencyKey, Long userId);

    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);

    /**
     * Releases a claim whose request failed, so a retry is not locked out until
     * the key expires. Scoped by id to make it impossible to release a record
     * this request did not create.
     */
    @Modifying
    @Query("DELETE FROM IdempotencyKey k WHERE k.id = :id")
    int release(@Param("id") Long id);
}
