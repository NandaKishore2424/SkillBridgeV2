package com.skillbridge.common.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One request's idempotency record.
 *
 * <p>Written {@code IN_PROGRESS} before the handler runs and completed after,
 * which is what lets a concurrent duplicate be told to wait rather than served
 * a half-written response.
 *
 * <p>{@code user_id} is a plain column rather than a {@code @ManyToOne}. This
 * row is written and read by an interceptor outside any business transaction,
 * on a path that must not lazily load a {@code User} or drag one into the
 * persistence context; the id is all it needs, and the foreign key in the
 * schema still enforces that the id is real.
 */
@Entity
@Table(name = "idempotency_keys")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Method and path, e.g. {@code POST /api/v1/admin/batches}. */
    @Column(name = "endpoint", nullable = false, length = 255)
    private String endpoint;

    /** SHA-256 of the raw request body, hex. Fixed 64 characters. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 20)
    private IdempotencyState state;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    /** A claim staked before the handler runs. No response yet, by construction. */
    public static IdempotencyKey inProgress(String key, Long userId, String endpoint,
                                            String requestHash, LocalDateTime expiresAt) {
        return IdempotencyKey.builder()
                .idempotencyKey(key)
                .userId(userId)
                .endpoint(endpoint)
                .requestHash(requestHash)
                .state(IdempotencyState.IN_PROGRESS)
                .createdAt(LocalDateTime.now())
                .expiresAt(expiresAt)
                .build();
    }

    /**
     * Records the response this key will replay.
     *
     * <p>Status and {@code completedAt} are set together with the state because
     * {@code ck_idempotency_completed} requires it: a COMPLETED row with no
     * status would replay as an empty 0, which a client cannot tell from a
     * dropped connection.
     */
    public void complete(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
        this.state = IdempotencyState.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public boolean isInProgress() {
        return state == IdempotencyState.IN_PROGRESS;
    }
}
