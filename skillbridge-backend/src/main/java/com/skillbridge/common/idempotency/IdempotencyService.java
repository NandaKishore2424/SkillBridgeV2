package com.skillbridge.common.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The database side of the idempotency contract.
 *
 * <p><b>Every method here runs in its own transaction, and that is the whole
 * design.</b> The claim is written before the handler runs and the completion
 * after it, so neither can share the handler's transaction — if they did, a
 * business rollback would erase the claim and a retry would re-execute the
 * operation the claim exists to prevent. {@code REQUIRES_NEW} says so
 * explicitly rather than relying on the interceptor happening to be called
 * outside one.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    /**
     * How long a completed response stays replayable. Long enough to cover a
     * client's retry budget, short enough that the table does not become an
     * archive of every write the system has served.
     */
    public static final Duration RETENTION = Duration.ofHours(24);

    /**
     * Responses larger than this are not stored. The point of the cap is that a
     * replay must not be able to push an arbitrary amount of a caller's data
     * into this table; the endpoints carrying the annotation return a single
     * created resource, which is far below it.
     */
    static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final IdempotencyKeyRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<IdempotencyKey> find(String key, Long userId) {
        return repository.findByIdempotencyKeyAndUserId(key, userId);
    }

    /**
     * Stakes a claim, throwing {@link DataIntegrityViolationException} if
     * someone else got there first.
     *
     * <p>Losing that race is not an error condition — it is the mechanism. Two
     * identical requests can both find nothing and both try to insert; the
     * unique constraint on {@code (idempotency_key, user_id)} is what makes
     * exactly one of them win. A pre-check alone cannot do this, because there
     * is no moment between the check and the insert that another transaction
     * cannot use.
     *
     * <p><b>The catch has to be in the caller, not here.</b> Catching the
     * violation inside this method looks tidier and does not work: by the time
     * the exception is thrown Spring has already marked the transaction
     * rollback-only, so swallowing it merely defers the failure to commit time,
     * where it resurfaces as {@code UnexpectedRollbackException: Transaction
     * silently rolled back because it has been marked as rollback-only}. That
     * was the first version of this class, and the concurrency test is what
     * found it. {@code REQUIRES_NEW} keeps the rollback contained to this
     * method's own transaction so the caller can handle it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyKey claim(String key, Long userId, String endpoint, String requestHash) {
        return repository.saveAndFlush(IdempotencyKey.inProgress(
                key, userId, endpoint, requestHash, LocalDateTime.now().plus(RETENTION)));
    }

    /**
     * Removes a record so its key can be claimed again.
     *
     * <p>Used for an expired record found still occupying the unique constraint.
     * Treating it as absent is not enough on its own: the row is still there,
     * and the insert that follows would collide with it. The purge job would
     * eventually remove it, but expiry must not depend on when a cron last ran.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void discard(Long recordId) {
        repository.release(recordId);
    }

    /** Records the response to replay. Silently skips a body over the cap. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long recordId, int status, String body) {
        repository.findById(recordId).ifPresent(record -> {
            String stored = body;
            if (stored != null && stored.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
                log.warn("Idempotent response for record {} is over {} bytes; storing the status without a body. "
                        + "A replay will carry the status and an empty body.", recordId, MAX_RESPONSE_BYTES);
                stored = null;
            }
            record.complete(status, stored);
            repository.save(record);
        });
    }

    /**
     * Drops a claim whose request did not produce a replayable response.
     *
     * <p>Called for 5xx and for an exception escaping the handler. Leaving the
     * row {@code IN_PROGRESS} instead would lock the key out for the full
     * retention window and answer every retry with 409 — turning one transient
     * failure into 24 hours of refusals. Deleting it means the client's retry is
     * treated as a fresh request, which is what a retry after a server error
     * should be.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long recordId) {
        repository.release(recordId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeExpired() {
        return repository.deleteExpired(LocalDateTime.now());
    }
}
